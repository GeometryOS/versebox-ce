// SPDX-License-Identifier: GPL-3.0-only
// Copyright (C) 2026 VerseBox contributors

//! The native timing contract for the VerseBox brand lockup.
//! Compose draws the returned frame with platform text and theme-aware colors.

const INTRO_END: f32 = 2_200.0;

#[derive(Clone, Copy, Debug, PartialEq)]
struct Frame {
    mark_x: f32,
    head_x: f32,
    underline_x: f32,
    head_alpha: f32,
    underline_alpha: f32,
    name_alpha: f32,
    name_x: f32,
    upper_lid: f32,
    lower_lid: f32,
}

impl Frame {
    fn at(elapsed_ms: f32, reduced_motion: bool) -> Self {
        if reduced_motion {
            return Self {
                mark_x: 0.0,
                head_x: 0.0,
                underline_x: 0.0,
                head_alpha: 1.0,
                underline_alpha: 0.6,
                name_alpha: 1.0,
                name_x: 0.0,
                upper_lid: 0.0,
                lower_lid: 0.0,
            };
        }

        let t = elapsed_ms.clamp(0.0, INTRO_END);
        let mark_x = 124.0 * (1.0 - eased((t - 800.0) / 352.0, 0.2, 0.75, 0.25, 1.0));
        let head_progress = eased((t - 384.0) / 416.0, 0.25, 0.1, 0.25, 1.0);
        let underline_progress = eased((t - 256.0) / 480.0, 0.25, 0.1, 0.25, 1.0);
        let head_x = 30.0 * (1.0 - head_progress);
        let underline_x = -12.0 * (1.0 - underline_progress);
        let head_alpha = head_progress;
        let underline_alpha = 0.6 * underline_progress;
        let name_progress = eased((t - 1_024.0) / 320.0, 0.2, 0.75, 0.25, 1.0);
        let upper_lid = blink(t, 1_700.0, 200.0, 270.0, 1.0);
        let lower_lid = 0.55 * blink(t, 1_700.0, 220.0, 280.0, 1.0);

        Self {
            mark_x,
            head_x,
            underline_x,
            head_alpha,
            underline_alpha,
            name_alpha: name_progress,
            name_x: 12.0 * (1.0 - name_progress),
            upper_lid,
            lower_lid,
        }
    }

    fn packed(self) -> i64 {
        let bytes = [
            self.mark_x.round().clamp(0.0, 255.0) as u8,
            unit_byte(self.head_alpha),
            unit_byte(self.underline_alpha),
            unit_byte(self.name_alpha),
            self.name_x.round().clamp(0.0, 255.0) as u8,
            unit_byte(self.upper_lid),
            unit_byte(self.lower_lid),
        ];
        bytes
            .iter()
            .enumerate()
            .fold(0i64, |packed, (index, byte)| {
                packed | ((*byte as i64) << (index * 8))
            })
    }

    fn offsets_packed(self) -> i64 {
        (self.head_x.round().clamp(0.0, 30.0) as i64)
            | (((self.underline_x.round() + 128.0).clamp(0.0, 255.0) as i64) << 8)
    }
}

fn unit_byte(value: f32) -> u8 {
    (value.clamp(0.0, 1.0) * 255.0).round() as u8
}

fn blink(t: f32, start: f32, close_at: f32, reopen_at: f32, maximum: f32) -> f32 {
    if t < start {
        0.0
    } else if t < start + close_at {
        eased((t - start) / close_at, 0.45, 0.0, 0.55, 1.0) * maximum
    } else if t < start + reopen_at {
        maximum
    } else {
        (1.0 - eased(
            (t - start - reopen_at) / (500.0 - reopen_at),
            0.3,
            0.0,
            0.3,
            1.0,
        )) * maximum
    }
}

fn eased(progress: f32, x1: f32, y1: f32, x2: f32, y2: f32) -> f32 {
    let x = progress.clamp(0.0, 1.0);
    if x == 0.0 || x == 1.0 {
        return x;
    }
    let mut low = 0.0;
    let mut high = 1.0;
    for _ in 0..16 {
        let t = (low + high) * 0.5;
        if cubic(t, x1, x2) < x {
            low = t;
        } else {
            high = t;
        }
    }
    cubic((low + high) * 0.5, y1, y2)
}

fn cubic(t: f32, p1: f32, p2: f32) -> f32 {
    3.0 * (1.0 - t).powi(2) * t * p1 + 3.0 * (1.0 - t) * t.powi(2) * p2 + t.powi(3)
}

#[no_mangle]
pub extern "system" fn Java_me_kanadex_versebox_brand_NativeVerseBoxLogo_frame(
    _env: *mut core::ffi::c_void,
    _receiver: *mut core::ffi::c_void,
    elapsed_ms: i64,
    reduced_motion: u8,
) -> i64 {
    Frame::at(elapsed_ms as f32, reduced_motion != 0).packed()
}

#[no_mangle]
pub extern "system" fn Java_me_kanadex_versebox_brand_NativeVerseBoxLogo_offsets(
    _env: *mut core::ffi::c_void,
    _receiver: *mut core::ffi::c_void,
    elapsed_ms: i64,
    reduced_motion: u8,
) -> i64 {
    Frame::at(elapsed_ms as f32, reduced_motion != 0).offsets_packed()
}

#[cfg(test)]
mod tests {
    use super::Frame;

    #[test]
    fn lockup_enters_before_name_and_settles_on_the_html_timeline() {
        let start = Frame::at(0.0, false);
        assert_eq!(start.mark_x, 124.0);
        assert_eq!(start.head_alpha, 0.0);
        assert_eq!(start.head_x, 30.0);
        assert_eq!(start.underline_x, -12.0);
        assert_eq!(start.name_alpha, 0.0);

        let settled = Frame::at(1_400.0, false);
        assert_eq!(settled.mark_x, 0.0);
        assert_eq!(settled.head_alpha, 1.0);
        assert_eq!(settled.head_x, 0.0);
        assert_eq!(settled.underline_x, 0.0);
        assert_eq!(settled.name_alpha, 1.0);
    }

    #[test]
    fn eye_covers_close_once_after_the_lockup_enters() {
        assert_eq!(Frame::at(1_699.0, false).upper_lid, 0.0);
        assert_eq!(Frame::at(1_900.0, false).upper_lid, 1.0);
        assert_eq!(Frame::at(1_970.0, false).upper_lid, 1.0);
        assert_eq!(Frame::at(2_200.0, false).upper_lid, 0.0);
    }

    #[test]
    fn packed_offsets_keep_the_mark_parts_independent() {
        let start = Frame::at(0.0, false).offsets_packed() as u64;
        let settled = Frame::at(1_400.0, false).offsets_packed() as u64;
        assert_eq!(start & 0xff, 30);
        assert_eq!((start >> 8) & 0xff, 116);
        assert_eq!(settled & 0xff, 0);
        assert_eq!((settled >> 8) & 0xff, 128);
    }

    #[test]
    fn reduced_motion_skips_the_intro_and_blink() {
        let frame = Frame::at(0.0, true);
        assert_eq!(frame.mark_x, 0.0);
        assert_eq!(frame.head_alpha, 1.0);
        assert_eq!(frame.name_alpha, 1.0);
        assert_eq!(frame.upper_lid, 0.0);
    }

    #[test]
    fn packed_frame_preserves_all_seven_visual_channels() {
        let frame = Frame::at(1_920.0, false);
        let packed = frame.packed() as u64;
        assert_eq!((packed >> 40) & 0xff, 255);
        assert_eq!((packed >> 48) & 0xff, 140);
    }
}
