//! App protocol v1: postcard-encoded typed messages and 20-byte-safe BLE chunks.
//! BLE notification: 0xA7 magic, version(1), seq LE32, part u8, parts u8, length u8, body<=11.
//! App must reassemble, deduplicate, authenticate peer, and ACK if future replay is enabled.
use serde::{Serialize,Deserialize};
use hmac::{Hmac,Mac};
use sha3::Sha3_256;
type HmacSha3=Hmac<Sha3_256>;
use crate::engine::{SourceReport,PoolReport,Health,SourceId};
pub const VERSION:u8=1;
pub const PAYLOAD_PER_CHUNK:usize=11;
#[derive(Serialize,Deserialize,Debug,Clone)]
pub enum Message {
 Boot {version:u8, boot_id:u32, device:String},
 Progress {source:SourceId,status:Health,filled:u16,needed:u16},
 Source(SourceReport), Pool(PoolReport),
 WifiSummary {ap_count:u8,mean_rssi:i8,scan_duration_ms:u32},
 Diagnostic {uptime_ms:u64, free_heap:u32, radar_frames:u64, radar_errors:u64,ble_connected:bool},
 // Appended variants preserve postcard discriminants of v1 message variants.
 WifiMotion {score:u8, matched_aps:u8, previous_aps:u8, current_aps:u8},
 RadarSummary {engineering:bool, target_state:u8, moving_energy:u8,
     stationary_energy:u8, moving_distance_cm:u16,
     stationary_distance_cm:u16, detection_distance_cm:u16},
}
pub fn mac16(key:&[u8;32], domain:&[u8], data:&[u8])->[u8;16] {
 let mut m=HmacSha3::new_from_slice(key).expect("HMAC key size");
 m.update(domain);m.update(data);
 let bytes=m.finalize().into_bytes();
 let mut tag=[0u8;16];tag.copy_from_slice(&bytes[..16]);tag
}
pub fn encode_chunks(seq:u32,boot_id:u32,m:&Message,key:&[u8;32])->Result<Vec<[u8;20]>,postcard::Error> {
 let packet=postcard::to_allocvec(m)?;
 let mut signed=Vec::with_capacity(packet.len()+24);
 signed.extend_from_slice(&boot_id.to_le_bytes());
 signed.extend_from_slice(&seq.to_le_bytes());
 signed.extend_from_slice(&packet);
 let tag=mac16(key,b"NULLVALON-telemetry-v1\0",&signed);
 signed.extend_from_slice(&tag);
 let chunks=signed.chunks(PAYLOAD_PER_CHUNK).len();
 if chunks==0 || chunks>255 {return Ok(Vec::new());}
 let mut out=Vec::with_capacity(chunks);
 for (index,payload) in signed.chunks(PAYLOAD_PER_CHUNK).enumerate(){
  let mut b=[0u8;20];b[0]=0xA7;b[1]=VERSION;b[2..6].copy_from_slice(&seq.to_le_bytes());
  b[6]=index as u8;b[7]=chunks as u8;b[8]=payload.len() as u8;
  b[9..9+payload.len()].copy_from_slice(payload);
  out.push(b);
 }
 Ok(out)
}
#[cfg(test)] mod tests{use super::*;
 #[test] fn fragment(){let m=Message::Boot{version:1,boot_id:42,device:"NULLVALON".to_string()};let c=encode_chunks(1,42,&m,&[0x13;32]).unwrap();assert!(!c.is_empty());assert_eq!(c[0][0],0xa7);}
}
