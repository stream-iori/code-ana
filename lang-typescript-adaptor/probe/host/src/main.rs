use libloading::Library;
use serde_json::{Value, json};
use std::{env, fs, mem::ManuallyDrop, path::PathBuf};

type Analyze = unsafe extern "C" fn(*const u8, u64) -> u64;
type Read = unsafe extern "C" fn(u64, u64, *mut u8, u64) -> i64;
type Close = unsafe extern "C" fn(u64) -> i32;

fn run(args: &[String]) {
    // Go runtime owns threads until process exit; never unload the library.
    let library = ManuallyDrop::new(unsafe { Library::new(&args[1]).unwrap() });
    let version = unsafe {
        library
            .get::<unsafe extern "C" fn() -> u32>(b"codeana_ts_probe_abi_version")
            .unwrap()
    };
    assert_eq!(unsafe { version() }, 1);
    let analyze: Analyze = unsafe { *library.get(b"codeana_ts_probe_analyze").unwrap() };
    let read: Read = unsafe { *library.get(b"codeana_ts_probe_read").unwrap() };
    let close: Close = unsafe { *library.get(b"codeana_ts_probe_close").unwrap() };
    let fixture = PathBuf::from(&args[2]);
    let expected: Value =
        serde_json::from_slice(&fs::read(fixture.join("expected.json")).unwrap()).unwrap();
    let mut files = serde_json::Map::new();
    let capture_dir = PathBuf::from(&args[3]);
    fs::create_dir(&capture_dir).unwrap();
    for name in ["a.ts", "b.ts"] {
        let path = capture_dir.join(name);
        fs::copy(fixture.join(name), &path).unwrap();
        files.insert(
            format!("/src/{name}"),
            json!(fs::read_to_string(&path).unwrap()),
        );
        fs::write(&path, "changed after capture").unwrap();
        fs::remove_file(path).unwrap();
    }
    let mut input = serde_json::to_vec(&json!({"files": files})).unwrap();
    assert_eq!(unsafe { analyze(std::ptr::null(), 0) }, 0);
    assert_eq!(unsafe { analyze(std::ptr::null(), 1) }, 0);
    assert_eq!(unsafe { analyze(input.as_ptr(), (1 << 20) + 1) }, 0);
    for bad in [b"".as_slice(), b"bad json", b"{}", b"{\"files\":{}}"] {
        assert_eq!(unsafe { analyze(bad.as_ptr(), bad.len() as u64) }, 0);
    }
    let handle = unsafe { analyze(input.as_ptr(), input.len() as u64) };
    assert_ne!(handle, 0);
    input.fill(0xaa);
    let mut output = Vec::new();
    loop {
        // A single byte of capacity with canaries on both sides.
        let mut buffer = [0x5a, 0, 0xa5];
        let count = unsafe { read(handle, output.len() as u64, buffer.as_mut_ptr().add(1), 1) };
        assert_eq!(buffer[0], 0x5a);
        assert_eq!(buffer[2], 0xa5);
        assert!((0..=1).contains(&count));
        if count == 0 {
            break;
        }
        output.push(buffer[1]);
        assert!(output.len() <= 1 << 20);
    }
    let actual: Value = serde_json::from_slice(&output).unwrap();
    assert_eq!(actual, expected);
    let mut buffer = [0u8; 128];
    assert_eq!(
        unsafe { read(handle, 0, buffer.as_mut_ptr(), 128) },
        128.min(output.len()) as i64
    );
    assert_eq!(
        unsafe { read(handle, u64::MAX, buffer.as_mut_ptr(), 1) },
        -1
    );
    assert_eq!(unsafe { read(handle, 0, std::ptr::null_mut(), 1) }, -1);
    assert_eq!(unsafe { read(handle, 0, buffer.as_mut_ptr(), 0) }, -1);
    assert_eq!(
        unsafe { read(handle, 0, buffer.as_mut_ptr(), (1 << 20) + 1) },
        -1
    );
    assert_eq!(
        unsafe { read(handle, output.len() as u64, buffer.as_mut_ptr(), 1) },
        0
    );
    assert_eq!(unsafe { close(handle) }, 0);
    assert_eq!(unsafe { close(handle) }, -1);
    assert_eq!(unsafe { read(handle, 0, buffer.as_mut_ptr(), 1) }, -1);
    assert_eq!(unsafe { close(u64::MAX) }, -1);
    assert_eq!(unsafe { read(u64::MAX, 0, buffer.as_mut_ptr(), 1) }, -1);
    fs::remove_dir(capture_dir).unwrap();
    println!("probe semantic and ABI checks passed");
    if args.get(4).is_some_and(|v| v == "wait-signal") {
        use std::io::Write;
        std::io::stdout().flush().unwrap();
        loop {
            std::thread::park();
        }
    }
}

fn main() {
    let args: Vec<_> = env::args().collect();
    assert!(
        args.len() >= 4,
        "LIB FIXTURE NEW_CAPTURE_DIR [thread|wait-signal]"
    );
    if args.get(4).is_some_and(|v| v == "thread") {
        std::thread::spawn(move || run(&args)).join().unwrap();
    } else {
        run(&args);
    }
}
