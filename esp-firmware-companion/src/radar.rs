//! HLK-LD2410C UART streaming parser for *observed* data only.
//! UART: 256000 8N1, 3.3V logic. Module needs a regulated 5V supply.
use serde::{Deserialize, Serialize};
const HEADER: [u8; 4] = [0xF4, 0xF3, 0xF2, 0xF1];
const FOOTER: [u8; 4] = [0xF8, 0xF7, 0xF6, 0xF5];

// Configuration commands for requesting engineering-mode telemetry.
pub const ENTER_CONFIG: [u8; 14] = [0xFD,0xFC,0xFB,0xFA,4,0,0xFF,0,1,0,4,3,2,1];
pub const ENGINEERING_ON: [u8; 12] = [0xFD,0xFC,0xFB,0xFA,2,0,0x62,0,4,3,2,1];
pub const EXIT_CONFIG: [u8; 12] = [0xFD,0xFC,0xFB,0xFA,2,0,0xFE,0,4,3,2,1];

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct RadarReport {
    pub engineer: bool,
    pub target_state: u8,
    pub moving_distance_cm: u16,
    pub moving_energy: u8,
    pub stationary_distance_cm: u16,
    pub stationary_energy: u8,
    pub detection_distance_cm: u16,
    // Only valid when `engineer == true`.  No invented gate values in basic mode.
    pub moving_gate_energy: Option<[u8; 9]>,
    pub stationary_gate_energy: Option<[u8; 9]>,
}
impl RadarReport {
    fn parse(p: &[u8]) -> Option<Self> {
        if p.len() < 13 || p[1] != 0xAA || p[2] > 3 { return None; }
        let engineering = match p[0] { 1 => true, 2 => false, _ => return None };
        // A basic frame has exactly 13 payload bytes; trailing 0x55, 0x00 must be present.
        if !engineering && (p.len() != 13 || p[11..13] != [0x55, 0]) { return None; }
        // Engineering frame contains 2 max-gate bytes and 9+9 measured gate energies.
        // Optional module-specific extension bytes can follow, before trailing 0x55 0x00.
        if engineering && (p.len() < 33 || p[p.len()-2..] != [0x55, 0]) { return None; }
        let (moving, stationary) = if engineering {
            let mut m=[0u8; 9]; m.copy_from_slice(&p[13..22]);
            let mut s=[0u8; 9]; s.copy_from_slice(&p[22..31]);
            (Some(m), Some(s))
        } else { (None, None) };
        Some(Self {
            engineer: engineering, target_state: p[2],
            moving_distance_cm: u16::from_le_bytes([p[3],p[4]]),
            moving_energy: p[5],
            stationary_distance_cm: u16::from_le_bytes([p[6],p[7]]),
            stationary_energy: p[8],
            detection_distance_cm: u16::from_le_bytes([p[9],p[10]]),
            moving_gate_energy: moving, stationary_gate_energy: stationary,
        })
    }
}

pub struct Decoder {
    buffer: Vec<u8>,
    pub received: u64,
    pub valid: u64,
    pub bad: u64,
    pub engineering: u64,
    pub basic: u64,
}
impl Decoder {
    pub fn new() -> Self {
        Self { buffer: Vec::with_capacity(192), received: 0, valid: 0,
            bad: 0, engineering: 0, basic: 0 }
    }
    pub fn feed(&mut self, bytes: &[u8]) -> Vec<RadarReport> {
        self.received = self.received.saturating_add(bytes.len() as u64);
        self.buffer.extend_from_slice(bytes);
        let mut output=Vec::new();
        loop {
            let Some(start)=self.buffer.windows(4).position(|w|w==HEADER) else {
                if self.buffer.len()>3 {let remove=self.buffer.len()-3;self.buffer.drain(..remove);}
                break;
            };
            if start>0 {self.buffer.drain(..start);}
            if self.buffer.len()<6 {break;}
            let payload_len=u16::from_le_bytes([self.buffer[4],self.buffer[5]]) as usize;
            if payload_len>128 || payload_len<13 {self.bad+=1;self.buffer.drain(..1);continue;}
            let total=6+payload_len+4;
            if self.buffer.len()<total {break;}
            if self.buffer[total-4..total]!=FOOTER {self.bad+=1;self.buffer.drain(..1);continue;}
            if let Some(report)=RadarReport::parse(&self.buffer[6..6+payload_len]) {
                self.valid+=1;
                if report.engineer {self.engineering+=1;} else {self.basic+=1;}
                output.push(report);
            } else {self.bad+=1;}
            self.buffer.drain(..total);
        }
        output
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    fn frame(data: &[u8]) -> Vec<u8> {
        let mut f=HEADER.to_vec();
        f.extend_from_slice(&(data.len() as u16).to_le_bytes());
        f.extend_from_slice(data); f.extend_from_slice(&FOOTER); f
    }
    #[test]
    fn basic_no_fabricated_gate_data() {
        let p=[2,0xAA,1,33,0,44,55,0,66,77,0,0x55,0];
        let f=frame(&p);let mut d=Decoder::new();
        assert!(d.feed(&f[..5]).is_empty());
        let reports=d.feed(&f[5..]);
        assert_eq!(reports.len(),1);
        assert_eq!(reports[0].moving_energy,44);
        assert!(reports[0].moving_gate_energy.is_none());
        assert_eq!(d.basic,1);
    }
    #[test]
    fn engineering_measured_gates() {
        let mut p=vec![1,0xAA,3,33,0,44,55,0,66,77,0,8,8];
        p.extend(1..=9);p.extend(11..=19);p.extend_from_slice(&[0x55,0]);
        let mut d=Decoder::new();let r=d.feed(&frame(&p));
        assert_eq!(r.len(),1);
        assert_eq!(r[0].moving_gate_energy.unwrap()[0],1);
        assert_eq!(r[0].stationary_gate_energy.unwrap()[8],19);
        assert_eq!(d.engineering,1);
    }
    #[test]
    fn reject_corrupt_footer() {
        let p=[2,0xAA,1,33,0,44,55,0,66,77,0,0x55,0];
        let mut f=frame(&p); *f.last_mut().unwrap()=0;
        let mut d=Decoder::new();assert!(d.feed(&f).is_empty());
        assert!(d.bad>0);
    }
}
