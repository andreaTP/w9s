#[export_name = "greet"]
pub extern "C" fn greet(ptr: *const u8, len: usize) {
    let name = unsafe { std::str::from_utf8_unchecked(std::slice::from_raw_parts(ptr, len)) };
    println!("Hello, {name}!");
}

fn main() {
    let name = "World";
    greet(name.as_ptr(), name.len());
}
