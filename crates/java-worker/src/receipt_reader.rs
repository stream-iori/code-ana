//! Prepaid Java receipt probe: ignored JSON cannot inflate strings/stack or visit unreserved nodes.
use std::io::{self, Read};
pub struct ReceiptReader<R> {
    input: R,
    in_string: bool,
    escaped: bool,
    string_bytes: usize,
    depth: usize,
    atom: bool,
    nodes: u64,
    max_nodes: u64,
}
impl<R: Read> ReceiptReader<R> {
    pub fn new(input: R, max_nodes: u64) -> Self {
        Self {
            input,
            in_string: false,
            escaped: false,
            string_bytes: 0,
            depth: 0,
            atom: false,
            nodes: 0,
            max_nodes,
        }
    }
    fn byte(&mut self, byte: u8) -> io::Result<()> {
        let fail = || io::Error::new(io::ErrorKind::InvalidData, "JAVA_RECEIPT_CONTROL_BUDGET");
        if self.in_string {
            if !self.escaped && byte == b'"' {
                self.in_string = false;
                return Ok(());
            }
            self.string_bytes += 1;
            if self.string_bytes > 16 * 1024 {
                return Err(fail());
            }
            if self.escaped {
                self.escaped = false;
            } else if byte == b'\\' {
                self.escaped = true;
            }
        } else {
            match byte {
                b'"' => {
                    self.nodes += 1;
                    self.in_string = true;
                    self.string_bytes = 0;
                    self.atom = false;
                }
                b'{' | b'[' => {
                    self.nodes += 1;
                    self.depth += 1;
                    self.atom = false;
                    if self.depth > 32 {
                        return Err(fail());
                    }
                }
                b'}' | b']' => {
                    self.depth = self.depth.saturating_sub(1);
                    self.atom = false;
                }
                b',' | b':' | b' ' | b'\n' | b'\r' | b'\t' => self.atom = false,
                _ => {
                    if !self.atom {
                        self.nodes += 1;
                        self.atom = true;
                    }
                }
            }
            if self.nodes > self.max_nodes {
                return Err(fail());
            }
        }
        Ok(())
    }
}
impl<R: Read> Read for ReceiptReader<R> {
    fn read(&mut self, output: &mut [u8]) -> io::Result<usize> {
        let n = self.input.read(output)?;
        for byte in &output[..n] {
            self.byte(*byte)?;
        }
        Ok(n)
    }
}
#[cfg(test)]
mod tests {
    use super::*;
    #[test]
    fn oversized_ignored_keys_strings_depth_and_node_counts_fail_before_decode_inflates_them() {
        for (input, nodes) in [
            (format!("{{\"{}\":0}}", "x".repeat(20 * 1024)), 128),
            (
                format!("{{\"ignored\":\"{}\"}}", "x".repeat(20 * 1024)),
                128,
            ),
            (format!("{}0{}", "[".repeat(33), "]".repeat(33)), 128),
            ("[0,0,0,0]".into(), 4),
        ] {
            assert!(
                serde_json::from_reader::<_, serde_json::Value>(ReceiptReader::new(
                    input.as_bytes(),
                    nodes
                ))
                .is_err()
            );
        }
        for length in [16 * 1024, 16 * 1024 + 1] {
            let input = format!("\"{}\"", "x".repeat(length));
            assert_eq!(
                serde_json::from_reader::<_, serde_json::Value>(ReceiptReader::new(
                    input.as_bytes(),
                    1
                ))
                .is_ok(),
                length == 16 * 1024
            );
        }
        let input = br#"{"ignored":["escaped\"[]",{"ok":true}],"usage_known":false}"#;
        let value: serde_json::Value =
            serde_json::from_reader(ReceiptReader::new(input.as_slice(), 32)).unwrap();
        assert_eq!(value["usage_known"], false);
    }
}
