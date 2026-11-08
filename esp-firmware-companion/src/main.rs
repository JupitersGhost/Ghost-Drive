//! Nullvalon v0.2 vehicle entropy node for Ghost Drive.
//! No GPS or internet credentials on the ESP32-C3: Android handles upstream services.
mod engine;
mod radar;
mod transport;
mod ble;
mod thermal;

use engine::{ConditionerPool, SourceId, SourceWindow};
use transport::Message;
use esp_idf_svc::{
    hal::{peripherals::Peripherals, uart::{self, UartDriver}, units::Hertz, gpio::AnyIOPin},
    eventloop::EspSystemEventLoop,
    wifi::{EspWifi, Configuration, ClientConfiguration, config::{ScanConfig, ScanType}},
};
use std::{time::{Instant,Duration},sync::mpsc::{self,Receiver}};
const KEY: &[u8; 32] = include_bytes!("../.secrets/device.key");

struct WifiObservation {
    bytes: Vec<u8>,
    ap_count: u8,
    mean_rssi: i8,
    duration_ms: u32,
    motion_score: Option<u8>,
    matched_aps: u8,
    previous_aps: u8,
}

// Ephemeral AP identifiers are used locally to compare successive observations;
// they are NOT included in entropy messages, logs, or GATT payloads.
fn spawn_wifi(
    modem: esp_idf_svc::hal::modem::Modem<'static>,
    sysloop: EspSystemEventLoop,
) -> Receiver<WifiObservation> {
    let (tx,rx) = mpsc::sync_channel(4);
    std::thread::Builder::new()
        .name("nullvalon-wifi".into()).stack_size(10240)
        .spawn(move || {
            let mut wifi=match EspWifi::new(modem,sysloop,None) {
                Ok(w)=>w, Err(e)=>{log::error!("wifi init: {e:?}");return;}
            };
            if let Err(e)=wifi.set_configuration(&Configuration::Client(ClientConfiguration::default())) {
                log::error!("wifi config: {e:?}");return;
            }
            if let Err(e)=wifi.start(){log::error!("wifi start: {e:?}");return;}
            let mut previous: Option<Vec<([u8;6],i8)>>=None;
            loop {
                let now=Instant::now();
                // Zero invokes IDF's default passive dwell, compatible with BLE coexistence.
                let scan=ScanConfig {scan_type:ScanType::Passive(Duration::from_millis(0)),
                    show_hidden:false,..Default::default()};
                if let Err(e)=wifi.start_scan(&scan,true){
                    log::warn!("wifi passive scan: {e:?}");
                } else {
                    match wifi.get_scan_result() {
                        Ok(aps)=>{
                            let mut bytes=Vec::with_capacity(aps.len()*3+8);
                            let mut now_ids=Vec::with_capacity(aps.len());
                            let mut sum=0i32;
                            for ap in &aps {
                                bytes.push(ap.signal_strength as u8);
                                bytes.push(ap.channel);
                                sum+=ap.signal_strength as i32;
                                now_ids.push((ap.bssid,ap.signal_strength));
                            }
                            let ap_count=aps.len().min(255) as u8;
                            bytes.extend_from_slice(&(aps.len().min(u16::MAX as usize) as u16).to_le_bytes());
                            let mean=if aps.is_empty(){0}else{(sum/aps.len() as i32) as i8};
                            let mut motion_score=None;
                            let mut matched=0u8;
                            let prev_count=previous.as_ref().map_or(0,|v|v.len().min(255) as u8);
                            if let Some(ref old)=previous {
                                let mut absolute_change=0u32;
                                let mut overlap=0u32;
                                for (address,rssi) in &now_ids {
                                    if let Some((_,prior))=old.iter().find(|(id,_)|id==address) {
                                        let delta=(*rssi as i32-*prior as i32).unsigned_abs().min(255) as u8;
                                        bytes.push(delta); // actual observed inter-scan changes
                                        absolute_change+=delta as u32;
                                        overlap+=1;
                                    }
                                }
                                matched=overlap.min(255) as u8;
                                let union=(old.len()+now_ids.len()).saturating_sub(overlap as usize);
                                let difference=if union==0 {0} else {
                                    (100*(union-overlap as usize)/union) as u32
                                };
                                let mean_delta=if overlap==0 {0} else {absolute_change/overlap};
                                // Heuristic RF environment change, NOT velocity or proof of vehicle motion.
                                let score=(mean_delta.saturating_mul(3)+difference/2).min(100) as u8;
                                motion_score=Some(score);
                                bytes.push(score);
                            }
                            previous=Some(now_ids);
                            let obs=WifiObservation{bytes,ap_count,mean_rssi:mean,
                                duration_ms:now.elapsed().as_millis() as u32,
                                motion_score,matched_aps:matched,previous_aps:prev_count};
                            if tx.try_send(obs).is_err(){log::warn!("wifi observation queue full");}
                        },
                        Err(e)=>log::warn!("wifi scan result: {e:?}"),
                    }
                }
                std::thread::sleep(Duration::from_secs(30));
            }
        }).expect("WiFi task spawn failed");
    rx
}

fn record(id: SourceId,input: &[u8],ms:u64,
    sources:&mut [SourceWindow;engine::N_SOURCES],pool:&mut ConditionerPool,
    ble:&mut ble::BleTelemetry)
{
    for (summary,hash) in sources[id.index()].push(input,ms){
        let r=summary.window.as_ref().expect("completed source window");
        log::info!("RAW {} win={} {:?} n={} H={:.3} Hmin={:.3} Markov={:.3} chi2={:.1}/{} p~={:.4} RCT=max{} pass={} APT=max{} pass={}",
            id.name(),summary.window_id,summary.status,r.sample_count,r.shannon_bits_per_byte,
            r.min_entropy_bits_per_byte,r.markov_min_bits_per_bit,r.chi_squared_uniform,
            r.chi_squared_dof,r.chi_squared_p_approx,r.rct_max_run_bytes,r.rct_pass,
            r.apt_max_frequency_in_512,r.apt_pass);
        if let Some(c)=summary.conditioned.as_ref(){
            log::info!("COND {} n={} H={:.3} Hmin={:.3} Markov={:.3} chi2={:.1}/{} p~={:.4} RCT={} APT={}",
                id.name(),c.sample_count,c.shannon_bits_per_byte,c.min_entropy_bits_per_byte,
                c.markov_min_bits_per_bit,c.chi_squared_uniform,c.chi_squared_dof,
                c.chi_squared_p_approx,c.rct_pass,c.apt_pass);
        }else{log::warn!("COND {} not generated; quarantined",id.name());}
        log::info!("SOURCE_TOTAL {} collected={} accepted={} rejected={} credited_bits={}",
            id.name(),summary.collected_bytes,summary.accepted_bytes,
            summary.rejected_bytes,summary.credited_bits);
        ble.publish(&Message::Source(summary));
        if let Some(d)=hash {
            let report=pool.add(id,d,ms);
            log::info!("POOL seq={} fresh={} mask=0x{:02x} H={:.3} Hmin={:.3} Markov={:.3} chi2={:.1}/{} p~={:.4} credited_bits={}",
                report.window_id,report.fresh_source.name(),report.accepted_sources_mask,
                report.shannon_bits_per_byte,report.min_entropy_bits_per_byte,
                report.markov_min_bits_per_bit,report.chi_squared_uniform,
                report.chi_squared_dof,report.chi_squared_p_approx,report.credited_bits);
            ble.publish(&Message::Pool(report));
        }
    }
}

fn configure_radar(u:&UartDriver<'_>) {
    // Check command writes; engineering mode is VERIFIED by receiving engineering frames.
    for (label,command) in [
        ("enter config",radar::ENTER_CONFIG.as_slice()),
        ("enable engineering",radar::ENGINEERING_ON.as_slice()),
        ("exit config",radar::EXIT_CONFIG.as_slice()),
    ] {
        match u.write(command) {
            Ok(n) if n==command.len()=>log::info!("radar command: {label} sent"),
            Ok(n)=>log::warn!("radar {label}: short write {n}/{}",command.len()),
            Err(e)=>log::warn!("radar {label} failed: {e:?}"),
        }
        std::thread::sleep(Duration::from_millis(180));
    }
}

fn run()->Result<(),Box<dyn std::error::Error>> {
    esp_idf_svc::sys::link_patches();
    esp_idf_svc::log::EspLogger::initialize_default();
    log::info!("NULLVALON v0.2 vehicle entropy node booting");
    let p=Peripherals::take()?;
    let sysloop=EspSystemEventLoop::take()?;
    let boot_id=unsafe{esp_idf_svc::sys::esp_random()};
    let mut ble=ble::BleTelemetry::start(boot_id,*KEY)?;
    let wifi_events=spawn_wifi(p.modem,sysloop);
    let thermal=thermal::Thermal::new();
    #[cfg(feature="radar-uart")]
    let radar_serial={
        // Seeed XIAO: D3/GPIO5 TX -> LD2410 RX, D2/GPIO4 RX <- LD2410 TX.
        let config=uart::config::Config::default().baudrate(Hertz(256_000));
        match UartDriver::new(p.uart1,p.pins.gpio5,p.pins.gpio4,
            Option::<AnyIOPin>::None,Option::<AnyIOPin>::None,&config){
            Ok(u)=>{log::info!("RADAR UART ready: 256000 8N1 GPIO5 TX / GPIO4 RX");Some(u)},
            Err(e)=>{log::error!("RADAR UART unavailable: {e:?}");None},
        }
    };
    #[cfg(feature="radar-uart")]
    if let Some(u)=radar_serial.as_ref(){configure_radar(u);}

    let mut decoder=radar::Decoder::new();
    let mut sources=std::array::from_fn(|i|SourceWindow::new(SourceId::ALL[i]));
    let mut pool=ConditionerPool::new();
    let uptime=Instant::now();
    let mut last_rng=Instant::now();
    let mut last_thermal=Instant::now();
    let mut last_health=Instant::now();
    let mut last_jitter=Instant::now();
    let mut last_progress=Instant::now();
    let mut last_radar_summary=Instant::now();
    let mut radar_last_seen:Option<radar::RadarReport>=None;
    let mut radar_last_seen_at_ms:Option<u64>=None;
    let mut last_radar_retry=Instant::now();
    let mut radar_retries=0u8;
    log::info!("RADAR will contribute ONLY after valid measured frames and source health acceptance");

    loop {
        let ms=uptime.elapsed().as_millis() as u64;
        if last_rng.elapsed()>=Duration::from_millis(250) {
            let mut buf=[0u8;512];
            for c in buf.chunks_mut(4){
                c.copy_from_slice(&unsafe{esp_idf_svc::sys::esp_random()}.to_le_bytes());
            }
            record(SourceId::HardwareRng,&buf,ms,&mut sources,&mut pool,&mut ble);
            last_rng=Instant::now();
        }
        let delta=last_jitter.elapsed().as_nanos() as u64;
        last_jitter=Instant::now();
        record(SourceId::Timing,&delta.to_le_bytes(),ms,&mut sources,&mut pool,&mut ble);
        if last_thermal.elapsed()>=Duration::from_millis(500) {
            if let Some(Ok(c))=thermal.as_ref().map(|t|t.get_celsius()) {
                let fixed=(c*100.0) as i16;
                record(SourceId::Thermal,&fixed.to_le_bytes(),ms,&mut sources,&mut pool,&mut ble);
            }
            last_thermal=Instant::now();
        }
        while let Ok(w)=wifi_events.try_recv(){
            log::info!("WIFI scan aps={} avg_rssi={}dBm duration={}ms bytes={}",
                w.ap_count,w.mean_rssi,w.duration_ms,w.bytes.len());
            if let Some(score)=w.motion_score {
                log::info!("WIFI RF-change score={} /100 matched={} previous={} (not vehicle speed)",
                    score,w.matched_aps,w.previous_aps);
                ble.publish(&Message::WifiMotion{score,matched_aps:w.matched_aps,
                    previous_aps:w.previous_aps,current_aps:w.ap_count});
            }
            if !w.bytes.is_empty() {
                record(SourceId::Wifi,&w.bytes,ms,&mut sources,&mut pool,&mut ble);
            }
            ble.publish(&Message::WifiSummary{ap_count:w.ap_count,
                mean_rssi:w.mean_rssi,scan_duration_ms:w.duration_ms});
        }
        #[cfg(feature="radar-uart")]
        {
            let mut rx=[0u8;256];
            if let Some(u)=radar_serial.as_ref(){
                match u.read(&mut rx,1){
                    Ok(n) if n>0 => {
                        for report in decoder.feed(&rx[..n]) {
                            // REAL measured energy values, never zero-filled fake gates.
                            let mut moving=Vec::with_capacity(10);
                            moving.push(report.moving_energy);
                            if let Some(gates)=report.moving_gate_energy.as_ref(){moving.extend_from_slice(gates);}
                            let mut stationary=Vec::with_capacity(10);
                            stationary.push(report.stationary_energy);
                            if let Some(gates)=report.stationary_gate_energy.as_ref(){stationary.extend_from_slice(gates);}
                            let mut distances=Vec::with_capacity(7);
                            distances.push(report.target_state);
                            distances.extend_from_slice(&report.moving_distance_cm.to_le_bytes());
                            distances.extend_from_slice(&report.stationary_distance_cm.to_le_bytes());
                            distances.extend_from_slice(&report.detection_distance_cm.to_le_bytes());
                            record(SourceId::RadarMoving,&moving,ms,&mut sources,&mut pool,&mut ble);
                            record(SourceId::RadarStationary,&stationary,ms,&mut sources,&mut pool,&mut ble);
                            record(SourceId::RadarDistance,&distances,ms,&mut sources,&mut pool,&mut ble);
                            radar_last_seen=Some(report);
                            radar_last_seen_at_ms=Some(ms);
                        }
                    },
                    Err(e)=>log::debug!("radar UART read: {e:?}"),
                    _=>{},
                }
            }
            // Some modules are not ready when the initial config commands arrive.
            if decoder.engineering==0 && radar_retries<3 &&
                last_radar_retry.elapsed()>=Duration::from_secs(20){
                if let Some(u)=radar_serial.as_ref(){
                    log::warn!("RADAR engineering data absent; retry {}/3",radar_retries+1);
                    configure_radar(u);
                    radar_retries+=1;
                }
                last_radar_retry=Instant::now();
            }
        }
        if last_radar_summary.elapsed()>=Duration::from_secs(5) {
            if radar_last_seen_at_ms.map_or(false,|at|ms.saturating_sub(at)>15_000) {
                radar_last_seen=None;
                radar_last_seen_at_ms=None;
            }
            if let Some(r)=radar_last_seen.as_ref(){
                log::info!("RADAR latest engineering={} state={} motion_energy={} still_energy={} distances_cm={}/{}/{}",
                    r.engineer,r.target_state,r.moving_energy,r.stationary_energy,
                    r.moving_distance_cm,r.stationary_distance_cm,r.detection_distance_cm);
                ble.publish(&Message::RadarSummary{engineering:r.engineer,target_state:r.target_state,
                    moving_energy:r.moving_energy,stationary_energy:r.stationary_energy,
                    moving_distance_cm:r.moving_distance_cm,
                    stationary_distance_cm:r.stationary_distance_cm,
                    detection_distance_cm:r.detection_distance_cm});
            }
            last_radar_summary=Instant::now();
        }
        if last_health.elapsed()>=Duration::from_secs(10){
            let (ble_pending,ble_dropped)=ble.queue_stats();
            log::info!("HEALTH heap={} radar_uart_bytes={} frames={} basic={} engineering={} bad={} ble_subscribed={} ble_pending_chunks={} ble_dropped_frames={}",
                unsafe{esp_idf_svc::sys::esp_get_free_heap_size()},decoder.received,
                decoder.valid,decoder.basic,decoder.engineering,decoder.bad,ble.subscribed(),
                ble_pending,ble_dropped);
            ble.publish(&Message::Diagnostic{uptime_ms:ms,
                free_heap:unsafe{esp_idf_svc::sys::esp_get_free_heap_size()},
                radar_frames:decoder.valid,radar_errors:decoder.bad,ble_connected:ble.subscribed()});
            last_health=Instant::now();
        }
        if last_progress.elapsed()>=Duration::from_secs(15){
            for s in &mut sources {
                s.mark_absent_if_stale(ms);
                log::info!("PROGRESS {} {:?} {}/{} bytes",s.id.name(),s.status,s.progress(),engine::WINDOW);
                ble.publish(&Message::Progress{source:s.id,status:s.status,
                    filled:s.progress() as u16,needed:engine::WINDOW as u16});
            }
            last_progress=Instant::now();
        }
        ble.pump();
        std::thread::sleep(Duration::from_millis(10));
    }
}
fn main(){
    if let Err(e)=run(){
        eprintln!("NULLVALON fatal: {e:?}");
        loop{std::thread::sleep(Duration::from_secs(5));}
    }
}
