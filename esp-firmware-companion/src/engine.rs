//! Platform-independent per-source observations, health diagnostics and conditioning.
//! IMPORTANT: output entropy statistics are descriptive; NEVER an entropy credit.
use serde::{Deserialize, Serialize};
use sha3::{Digest, Sha3_512};

pub const WINDOW: usize = 8192;
pub const N_SOURCES: usize = 7;

#[repr(u8)]
#[derive(Clone, Copy, Debug, Serialize, Deserialize)]
pub enum SourceId { HardwareRng=1, RadarMoving=2, RadarStationary=3, RadarDistance=4, Wifi=5, Timing=6, Thermal=7 }
impl SourceId {
    pub const ALL: [Self; N_SOURCES] = [Self::HardwareRng,Self::RadarMoving,Self::RadarStationary,Self::RadarDistance,Self::Wifi,Self::Timing,Self::Thermal];
    pub fn index(self) -> usize { self as usize - 1 }
    pub fn name(self) -> &'static str { match self {Self::HardwareRng=>"rng",Self::RadarMoving=>"radar_moving",Self::RadarStationary=>"radar_stationary",Self::RadarDistance=>"radar_distance",Self::Wifi=>"wifi",Self::Timing=>"timing",Self::Thermal=>"thermal"} }
    pub fn sample_kind(self) -> &'static str { match self {Self::HardwareRng=>"rng_byte",Self::RadarMoving=>"radar_moving_gate_energy",Self::RadarStationary=>"radar_stationary_gate_energy",Self::RadarDistance=>"radar_reported_distances",Self::Wifi=>"ap_rssi_and_counts",Self::Timing=>"monotonic_delta_bytes",Self::Thermal=>"temperature_quantized_byte"} }
}
#[derive(Clone, Copy, Debug, Serialize, Deserialize, PartialEq)]
pub enum Health { Absent, Collecting, Accepted, Quarantined }

#[derive(Clone, Debug, Serialize, Deserialize)]
pub struct Stats {
    pub sample_count: u32,
    pub alphabet: String,
    pub shannon_bits_per_byte: f32,
    pub min_entropy_bits_per_byte: f32,
    /// First-order binary-Markov conservative next-bit predictability diagnostic.
    pub markov_min_bits_per_bit: f32,
    pub chi_squared_uniform: f32,
    pub chi_squared_dof: u16,
    pub chi_squared_p_approx: f32,
    pub rct_max_run_bytes: u16,
    pub rct_pass: bool,
    pub apt_max_frequency_in_512: u16,
    pub apt_pass: bool,
}

// RCT/APT limits are intentionally conservative diagnostics for uniform-byte
// RNG samples, NOT SP800-90B validated cutoffs for arbitrary environmental data.
// These are profile-specific and must be calibrated from measured H_min.
/// Approximate upper-tail chi-square p using Wilson-Hilferty + normal CDF.
/// Diagnostic display only; assumes iid samples with uniform expected values.
fn chi2_p_approx(chi:f64,dof:f64)->f32 {
 let z=((chi/dof).cbrt()-(1.0-2.0/(9.0*dof)))/(2.0/(9.0*dof)).sqrt();
 // Abramowitz-Stegun normal CDF approximation, <=~1e-7 absolute error.
 let t=1.0/(1.0+0.2316419*z.abs());
 let density=(-0.5*z*z).exp()/2.5066282746310002;
 let tail=density*t*(0.319381530+t*(-0.356563782+t*(1.781477937+t*(-1.821255978+t*1.330274429))));
 (if z>=0.0 {tail} else {1.0-tail}).clamp(0.0,1.0) as f32
}

pub fn analyze(bytes: &[u8], rng_profile: bool) -> Stats {
    let n=bytes.len();
    let mut counts=[0usize; 256];
    let mut run=0usize; let mut max_run=0usize; let mut previous=None;
    let mut apt_max=0usize;
    for chunk in bytes.chunks(512) {
        if let Some(&first)=chunk.first() {
            apt_max=apt_max.max(chunk.iter().filter(|&&v|v==first).count());
        }
    }
    let mut transitions=[[0u32; 2]; 2];
    let mut prevbit:Option<usize>=None;
    for &x in bytes {
        counts[x as usize]+=1;
        if Some(x)==previous {run+=1;} else {run=1;previous=Some(x);}
        max_run=max_run.max(run);
        for b in (0..8).rev() {
            let bit=((x>>b)&1) as usize;
            if let Some(p)=prevbit {transitions[p][bit]+=1;}
            prevbit=Some(bit);
        }
    }
    let nf=(n.max(1)) as f64;
    let mut shannon=0.0f64;
    let mut high=0usize;
    let mut chi2=0.0f64;
    let expected=nf/256.0;
    for &v in &counts {
        if v>0 {let p=v as f64/nf;shannon-=p*p.log2();}
        high=high.max(v);
        let d=v as f64-expected;chi2+=d*d/expected;
    }
    let mut max_next=0.5f64;
    for row in &transitions {
        let total=row[0]+row[1];
        if total>0 { max_next=max_next.max(row[0].max(row[1]) as f64/total as f64); }
    }
    Stats {
        sample_count:n as u32,alphabet:"byte_0_255".into(),shannon_bits_per_byte:shannon as f32,
        min_entropy_bits_per_byte:-(high as f64/nf).log2() as f32,
        markov_min_bits_per_bit:-max_next.log2() as f32,
        chi_squared_uniform:chi2 as f32,chi_squared_dof:255,
        chi_squared_p_approx:chi2_p_approx(chi2,255.0),
        rct_max_run_bytes:max_run as u16,rct_pass: max_run < if rng_profile {32} else {128},
        apt_max_frequency_in_512:apt_max as u16,apt_pass:apt_max < if rng_profile {64} else {496},
    }
}

#[derive(Clone, Debug, Serialize, Deserialize)]
pub struct SourceReport {
    pub id: SourceId,
    pub sample_kind: String,
    pub status: Health,
    pub window_id: u32,
    pub uptime_ms: u64,
    pub window: Option<Stats>,
    pub conditioned: Option<Stats>,
    pub collected_bytes: u64,
    pub accepted_bytes: u64,
    pub rejected_bytes: u64,
    pub credited_bits: u64,
    pub conditioner_sha3_512: bool,
}
#[derive(Clone, Debug, Serialize, Deserialize)]
pub struct PoolReport {
    pub status: Health,
    pub window_id: u32,
    pub conditioned_digest: Vec<u8>,
    pub shannon_bits_per_byte: f32,
    pub min_entropy_bits_per_byte: f32,
    pub markov_min_bits_per_bit: f32,
    pub chi_squared_uniform: f32,
    pub chi_squared_dof: u16,
    pub chi_squared_p_approx: f32,
    pub accepted_sources_mask: u8,
    pub fresh_source: SourceId,
    pub newly_accepted_source_bytes: u32,
    pub credited_bits: u64,
}

pub struct SourceWindow {
    pub id:SourceId,pub status:Health,pub collected:u64,pub accepted:u64,pub rejected:u64,
    pub window_id:u32, data:Vec<u8>, conditioned_history:Vec<u8>,
    last_accepted_raw_digest:Option<[u8;64]>, last_seen_ms:Option<u64>,
}
impl SourceWindow {
    pub fn new(id:SourceId)->Self {Self{id,status:Health::Absent,collected:0,accepted:0,rejected:0,window_id:0,data:Vec::with_capacity(WINDOW),conditioned_history:Vec::with_capacity(4096),last_accepted_raw_digest:None,last_seen_ms:None}}
    pub fn push(&mut self, input:&[u8], uptime_ms:u64)->Vec<(SourceReport,Option<[u8;64]>)> {
        if !input.is_empty() {
            self.last_seen_ms=Some(uptime_ms);
            if self.status==Health::Absent {self.status=Health::Collecting;}
        }
        let mut out=Vec::new();
        for &b in input {
            self.data.push(b);self.collected+=1;
            if self.data.len()<WINDOW {continue;}
            self.window_id+=1;
            let rng_profile=matches!(self.id,SourceId::HardwareRng);
            let raw=analyze(&self.data,rng_profile);
            // Conservative *engineering* gates, not NIST SP 800-90B entropy credit.
            // Environmental streams can be highly predictable; don't accept them on
            // repetition-count and adaptive-proportion tests alone.
            let minimum_hmin = match self.id {
                SourceId::HardwareRng => 5.0,
                SourceId::RadarMoving | SourceId::RadarStationary | SourceId::RadarDistance => 0.40,
                SourceId::Wifi => 0.40,
                SourceId::Timing => 0.40,
                SourceId::Thermal => 0.20,
            };
            let mut fingerprint=Sha3_512::new();
            fingerprint.update(b"NULLVALON-raw-fingerprint-v2\0");
            fingerprint.update([self.id as u8]);
            fingerprint.update(&self.data);
            let raw_fingerprint:[u8;64]=fingerprint.finalize().into();
            let repeated = self.last_accepted_raw_digest == Some(raw_fingerprint);
            let passed=raw.rct_pass && raw.apt_pass
                && raw.min_entropy_bits_per_byte >= minimum_hmin
                && raw.markov_min_bits_per_bit >= 0.025
                && !repeated;
            let mut digest=None;
            let mut cond_stats=None;
            if passed {
                self.last_accepted_raw_digest=Some(raw_fingerprint);
                self.accepted+=WINDOW as u64;
                let mut hash=Sha3_512::new();
                hash.update(b"NULLVALON-source-v1\0");
                hash.update([self.id as u8]);hash.update(self.window_id.to_le_bytes());
                hash.update(&self.data);
                let d:[u8;64]=hash.finalize().into();
                self.conditioned_history.extend_from_slice(&d);
                if self.conditioned_history.len()>4096 {let remove=self.conditioned_history.len()-4096;self.conditioned_history.drain(..remove);}
                cond_stats=Some(analyze(&self.conditioned_history,false));digest=Some(d);
                self.status=Health::Accepted;
            } else {self.rejected+=WINDOW as u64;self.status=Health::Quarantined;}
            out.push((SourceReport{id:self.id,sample_kind:self.id.sample_kind().into(),status:self.status,
                window_id:self.window_id,uptime_ms,window:Some(raw),conditioned:cond_stats,
                collected_bytes:self.collected,accepted_bytes:self.accepted,rejected_bytes:self.rejected,
                credited_bits:0,conditioner_sha3_512:true},digest));
            self.data.clear();
        }
        out
    }
    pub fn progress(&self)->usize {self.data.len()}
    pub fn mark_absent_if_stale(&mut self,now_ms:u64) {
        if self.last_seen_ms.map_or(false,|then|now_ms.saturating_sub(then)>300_000) {
            self.status=Health::Absent;
            self.data.clear();
            self.last_seen_ms=None;
        }
    }
}

pub struct ConditionerPool {
    // Each contribution carries acceptance time, so absent sensors expire.
    last: [Option<([u8;64],u64)>;N_SOURCES],
    seq:u32, conditioned_history:Vec<u8>,
}
impl ConditionerPool {
    pub fn new()->Self {Self{last:[None;N_SOURCES],seq:0,conditioned_history:Vec::with_capacity(WINDOW)}}
    pub fn add(&mut self,source:SourceId,digest:[u8;64],uptime_ms:u64)->PoolReport {
        const FRESH_FOR_MS:u64=300_000; // Five-minute freshness limit
        self.last[source.index()]=Some((digest,uptime_ms));self.seq+=1;
        let mut sha=Sha3_512::new();sha.update(b"NULLVALON-pool-v2\0");
        sha.update(self.seq.to_le_bytes());
        let mut mask=0u8;
        for (i,m) in self.last.iter_mut().enumerate() {
            if m.as_ref().map_or(false,|(_,when)|uptime_ms.saturating_sub(*when)>FRESH_FOR_MS) {
                *m=None;
                continue;
            }
            if let Some((value,_when))=m.as_ref() {
                mask|=1<<i;
                sha.update([i as u8+1]);sha.update(value);
            }
        }
        let d:[u8;64]=sha.finalize().into();
        self.conditioned_history.extend_from_slice(&d);
        if self.conditioned_history.len()>WINDOW {let drop=self.conditioned_history.len()-WINDOW;self.conditioned_history.drain(..drop);}
        let s=analyze(&self.conditioned_history,false);
        PoolReport{status:Health::Accepted,window_id:self.seq,conditioned_digest:d.to_vec(),
            shannon_bits_per_byte:s.shannon_bits_per_byte,
            min_entropy_bits_per_byte:s.min_entropy_bits_per_byte,
            markov_min_bits_per_bit:s.markov_min_bits_per_bit,
            chi_squared_uniform:s.chi_squared_uniform,chi_squared_dof:s.chi_squared_dof,
            chi_squared_p_approx:s.chi_squared_p_approx,
            accepted_sources_mask:mask,fresh_source:source,newly_accepted_source_bytes:WINDOW as u32,credited_bits:0}
    }
}

#[cfg(test)]
mod tests {
 use super::*;
 #[test] fn stuck_rng_quarantined(){let mut s=SourceWindow::new(SourceId::HardwareRng);let reports=s.push(&vec![7;WINDOW],123);assert_eq!(reports.len(),1);assert_eq!(reports[0].0.status,Health::Quarantined);}
 #[test] fn counters_and_windows(){let mut s=SourceWindow::new(SourceId::Thermal);assert!(s.push(&vec![1;WINDOW-1],1).is_empty());let x=s.push(&[2],2);assert_eq!(x.len(),1);assert_eq!(s.collected,WINDOW as u64);}
 #[test] fn repeated_environmental_window_not_reused(){
     // Construct a varied physical-observation-like sequence with nontrivial bit transitions.
     let input=(0..WINDOW).map(|i|((i*17+((i/7)%89)*13)%256) as u8).collect::<Vec<_>>();
     let mut source=SourceWindow::new(SourceId::RadarMoving);
     let one=source.push(&input,1000);
     assert_eq!(one.len(),1);
     assert_eq!(one[0].0.status,Health::Accepted);
     let two=source.push(&input,2000);
     assert_eq!(two[0].0.status,Health::Quarantined);
 }
 #[test] fn expired_pool_source_excluded(){
     let mut p=ConditionerPool::new();
     p.add(SourceId::RadarMoving,[7;64],1000);
     let r=p.add(SourceId::HardwareRng,[8;64],400_000);
     assert_eq!(r.accepted_sources_mask,1);
 }
 #[test] fn uniform_histogram(){let sample=(0..WINDOW).map(|i|(i%256) as u8).collect::<Vec<_>>();let s=analyze(&sample,true);assert!((s.shannon_bits_per_byte-8.).abs()<0.001);assert!(s.rct_pass);}
}
