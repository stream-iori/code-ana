use libloading::{Library, Symbol};
use std::{ffi::c_void, path::PathBuf};

type CreateIsolate = unsafe extern "C" fn(*const c_void, *mut *mut c_void, *mut *mut c_void) -> i32;
type TearDownIsolate = unsafe extern "C" fn(*mut c_void) -> i32;
type JavaHello = unsafe extern "C" fn(*mut c_void) -> i32;

fn main() -> Result<(), Box<dyn std::error::Error>> {
    let path = std::env::args_os()
        .nth(1)
        .map(PathBuf::from)
        .ok_or("usage: codeana-java-worker <native-library-path>")?;
    if !path.is_file() {
        return Err(format!("native library missing: {}", path.display()).into());
    }
    // The native library stays in this process, never in the daemon.
    unsafe {
        let library = Library::new(&path)?;
        let create: Symbol<CreateIsolate> = library.get(b"graal_create_isolate")?;
        let tear_down: Symbol<TearDownIsolate> = library.get(b"graal_tear_down_isolate")?;
        let hello: Symbol<JavaHello> = library.get(b"codeana_java_hello")?;
        let mut isolate = std::ptr::null_mut();
        let mut thread = std::ptr::null_mut();
        let status = create(std::ptr::null(), &mut isolate, &mut thread);
        if status != 0 {
            return Err(format!("graal_create_isolate failed: {status}").into());
        }
        let value = hello(thread);
        let status = tear_down(thread);
        if status != 0 {
            return Err(format!("graal_tear_down_isolate failed: {status}").into());
        }
        println!("Hello from Java 25 via FFI: {value}");
    }
    Ok(())
}
