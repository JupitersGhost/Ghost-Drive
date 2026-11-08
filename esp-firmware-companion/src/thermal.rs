use esp_idf_svc::sys;
use std::ptr;

pub struct Thermal {
    handle: sys::temperature_sensor_handle_t,
}

impl Thermal {
    pub fn new() -> Option<Self> {
        // ESP32-C3 / ESP-IDF 6.0:
        // XTAL clock source = SOC_MOD_CLK_XTAL = 10.
        let mut config: sys::temperature_sensor_config_t =
            unsafe { std::mem::zeroed() };

        config.range_min = -10;
        config.range_max = 80;
        config.clk_src = 10;

        let mut handle = ptr::null_mut();

        let err = unsafe {
            sys::temperature_sensor_install(&config, &mut handle)
        };

        if err != sys::ESP_OK || handle.is_null() {
            log::warn!("Thermal sensor installation failed: {}", err);
            return None;
        }

        let err = unsafe {
            sys::temperature_sensor_enable(handle)
        };

        if err != sys::ESP_OK {
            log::warn!("Thermal sensor enable failed: {}", err);
            unsafe { sys::temperature_sensor_uninstall(handle); }
            return None;
        }

        log::info!("ESP32-C3 thermal sensor initialized");
        Some(Self { handle })
    }

    pub fn get_celsius(&self) -> Result<f32, i32> {
        let mut value = 0.0f32;
        let err = unsafe {
            sys::temperature_sensor_get_celsius(
                self.handle, &mut value
            )
        };

        if err == sys::ESP_OK {
            Ok(value)
        } else {
            Err(err)
        }
    }
}

impl Drop for Thermal {
    fn drop(&mut self) {
        unsafe {
            sys::temperature_sensor_disable(self.handle);
            sys::temperature_sensor_uninstall(self.handle);
        }
    }
}
