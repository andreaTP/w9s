use std::io::Write;
use std::mem;
use std::os::raw::{c_char, c_int};

#[no_mangle]
pub unsafe extern "C" fn rustc_demangle(
    mangled: *const c_char,
    out: *mut c_char,
    out_size: usize,
) -> c_int {
    let mangled_str = match std::ffi::CStr::from_ptr(mangled).to_str() {
        Ok(s) => s,
        Err(_) => return 0,
    };
    match rustc_demangle::try_demangle(mangled_str) {
        Ok(demangle) => {
            let mut out_slice = std::slice::from_raw_parts_mut(out as *mut u8, out_size);
            match write!(out_slice, "{:#}\0", demangle) {
                Ok(_) => 1,
                Err(_) => 0,
            }
        }
        Err(_) => 0,
    }
}

#[no_mangle]
pub unsafe extern "C" fn rustc_demangle_len(mangled: *const c_char) -> usize {
    let mangled_str = match std::ffi::CStr::from_ptr(mangled).to_str() {
        Ok(s) => s,
        Err(_) => return 0,
    };
    match rustc_demangle::try_demangle(mangled_str) {
        Ok(demangle) => demangle.to_string().len() + 1,
        Err(_) => 0,
    }
}

#[no_mangle]
pub extern "C" fn alloc(len: usize) -> *const u8 {
    let mut buf = Vec::with_capacity(len);
    let ptr = buf.as_mut_ptr();
    mem::forget(buf);
    ptr
}

#[no_mangle]
pub unsafe extern "C" fn dealloc(ptr: &mut u8, len: usize) {
    let _ = Vec::from_raw_parts(ptr, 0, len);
}
