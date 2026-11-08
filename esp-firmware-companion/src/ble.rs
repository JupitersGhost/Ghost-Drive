//! BLE GATT server. Device presence for Ghost Drive; compact structured telemetry.
use esp32_nimble::{BLEAdvertisementData,BLEDevice,NimbleProperties,uuid128};
use esp32_nimble::enums::{AuthReq,SecurityIOCap};
use std::sync::atomic::{AtomicBool,Ordering};
static BLE_AUTH_OK: AtomicBool = AtomicBool::new(false);
use crate::transport::mac16;
use crate::transport::{Message,encode_chunks};
use std::{sync::Arc,collections::VecDeque,time::{Duration,Instant}};
use esp32_nimble::BLECharacteristic;
use esp32_nimble::utilities::mutex::Mutex;

// A notification is copied into a finite NimBLE TX buffer. Calling notify on
// every 10 ms main-loop pass can exhaust those buffers before the controller
// has transmitted the earlier packets. Keep enough queue for telemetry bursts,
// but pace the transfer so the BLE stack and Android can drain it reliably.
const MAX_PENDING_CHUNKS:usize=128;
const NOTIFY_INTERVAL:Duration=Duration::from_millis(75);

// UUID namespace owned by Nullvalon. Do not change after Android release.
pub const SERVICE_UUID:&str="c7a80000-0dab-4d03-9a0b-a000a0000001";

pub struct BleTelemetry {
 chara: Arc<Mutex<BLECharacteristic>>,
 pub packet_seq:u32,
 pending: VecDeque<[u8;20]>,
 last_subscribed:bool,
 boot_id:u32,
 key:[u8;32],
 last_notify:Instant,
 dropped_frames:u32,
}
impl BleTelemetry {
 pub fn start(boot_id:u32,key:[u8;32])->Result<Self,Box<dyn std::error::Error>> {
  let device=BLEDevice::take();
  BLEDevice::set_device_name("NULLVALON")?;
  // TEMPORARY fixed PIN, replace for production.
  device.security()
      .set_auth(AuthReq::Bond | AuthReq::Mitm | AuthReq::Sc)
      .set_passkey(713932)
      .set_io_cap(SecurityIOCap::DisplayOnly)
      .resolve_rpa();
  let server=device.get_server();

  server.on_connect(|_,desc| {
      BLE_AUTH_OK.store(false,Ordering::Release);
      log::info!("BLE connected handle={}; pairing required",desc.conn_handle());
  });

  server.on_disconnect(|_,result| {
      BLE_AUTH_OK.store(false,Ordering::Release);
      log::info!("BLE disconnected: {result:?}");
  });

  server.on_authentication_complete(|_,desc,result| {
      let allowed=result.is_ok() && desc.encrypted() && desc.authenticated();
      BLE_AUTH_OK.store(allowed,Ordering::Release);
      log::info!("BLE pairing: allowed={} encrypted={} authenticated={} bonded={}",
          allowed,desc.encrypted(),desc.authenticated(),desc.bonded());
  });
  server.advertise_on_disconnect(true);
  let service=server.create_service(uuid128!("c7a80000-0dab-4d03-9a0b-a000a0000001"));
  let chara=service.lock().create_characteristic(
     uuid128!("c7a80001-0dab-4d03-9a0b-a000a0000001"),
     NimbleProperties::READ|NimbleProperties::READ_ENC|NimbleProperties::READ_AUTHEN|NimbleProperties::NOTIFY);
  chara.lock().set_value(b"NULLVALON/2 PIN-PAIR");
  // The phone writes a fresh 16-byte nonce to this encrypted GATT characteristic.
  // Proof is HMAC-SHA3-256(key, domain || nonce || boot_id), truncated to 16B.
  // This authenticates the ESP32 to the phone, not the phone to the ESP32.
  let challenge=service.lock().create_characteristic(
     uuid128!("c7a80002-0dab-4d03-9a0b-a000a0000001"),
     NimbleProperties::WRITE | NimbleProperties::WRITE_ENC | NimbleProperties::WRITE_AUTHEN);
  let proof=service.lock().create_characteristic(
     uuid128!("c7a80003-0dab-4d03-9a0b-a000a0000001"),
     NimbleProperties::READ | NimbleProperties::READ_ENC | NimbleProperties::READ_AUTHEN | NimbleProperties::NOTIFY);
  challenge.lock().on_write(move |args| {
      if args.recv_data().len()!=16 { args.reject();return; }
      let mut material=[0u8;20];material[..16].copy_from_slice(args.recv_data());
      material[16..].copy_from_slice(&boot_id.to_le_bytes());
      let proof_tag=mac16(&key,b"NULLVALON-auth-v1\0",&material);
      let mut proof_chr=proof.lock();proof_chr.set_value(&proof_tag);proof_chr.notify();
  });
  server.start()?;
  let mut adv=device.get_advertising().lock();
  // Keep advertising payload below legacy 31-byte limit.
  adv.set_data(BLEAdvertisementData::new().name("NULLVALON"))?;
  adv.start()?;
  Ok(Self{chara,packet_seq:0,boot_id,key,
      pending:VecDeque::with_capacity(MAX_PENDING_CHUNKS),last_subscribed:false,
      last_notify:Instant::now(),dropped_frames:0})
 }
 // Only stream telemetry after authenticated pairing.
 pub fn subscribed(&self)->bool{
  BLE_AUTH_OK.load(Ordering::Acquire) &&
  self.chara.lock().subscribed_count()>0
 }
 pub fn publish(&mut self,m:&Message){
  if !self.subscribed(){return;}
  self.packet_seq=self.packet_seq.wrapping_add(1);
  match encode_chunks(self.packet_seq,self.boot_id,m,&self.key) {
   Ok(chunks)=>{
    if chunks.len()+self.pending.len()>MAX_PENDING_CHUNKS {
      self.dropped_frames=self.dropped_frames.saturating_add(1);
      log::warn!("BLE telemetry backpressure; dropping whole frame pending_chunks={} dropped_frames={}",
          self.pending.len(),self.dropped_frames);
      return;
    }
    self.pending.extend(chunks);
   },
   Err(e)=>log::warn!("BLE encode failed: {e:?}"),
  }
 }
 pub fn pump(&mut self) {
  let is_subscribed=self.subscribed();
  if !is_subscribed {self.pending.clear();self.last_subscribed=false;return;}
  if !self.last_subscribed {
    self.last_subscribed=true;
    self.publish(&Message::Boot{version:1,boot_id:self.boot_id,device:"NULLVALON".into()});
  }
  if self.last_notify.elapsed()<NOTIFY_INTERVAL {return;}
  if let Some(chunk)=self.pending.pop_front() {
   let mut c=self.chara.lock();c.set_value(&chunk);c.notify();
   self.last_notify=Instant::now();
  }
 }
 pub fn queue_stats(&self)->(usize,u32){(self.pending.len(),self.dropped_frames)}
}
