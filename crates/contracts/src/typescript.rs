//! Private TS engine result admission; decoding stays in generated protobuf.
use crate::protobuf::typescript::v1 as p;
use anyhow::{Result, ensure};
use prost::Message;

fn fields(
    bytes: &[u8],
    allowed: u64,
    mut nested: impl FnMut(u64, &[u8]) -> Result<()>,
) -> Result<u64> {
    let mut seen = 0;
    crate::wire_fields(bytes, |tag, value| {
        ensure!(
            tag < 64 && allowed & (1u64 << tag) != 0,
            "TYPESCRIPT_RESULT_FIELD"
        );
        let bit = 1u64 << tag;
        ensure!(seen & bit == 0, "TYPESCRIPT_RESULT_DUPLICATE");
        seen |= bit;
        nested(tag, value)
    })?;
    Ok(seen)
}
fn mask(tags: &[u64]) -> u64 {
    tags.iter().fold(0, |value, tag| value | (1 << tag))
}
fn scalars(mut bytes: &[u8], limits: &[(u32, u64)]) -> Result<()> {
    while !bytes.is_empty() {
        let (tag, kind) = prost::encoding::decode_key(&mut bytes)?;
        if let Some((_, max)) = limits.iter().find(|(field, _)| *field == tag) {
            ensure!(
                kind == prost::encoding::WireType::Varint,
                "TYPESCRIPT_RESULT_SCALAR_TYPE"
            );
            ensure!(
                prost::encoding::decode_varint(&mut bytes)? <= *max,
                "TYPESCRIPT_RESULT_SCALAR_RANGE"
            );
        } else {
            prost::encoding::skip_field(
                kind,
                tag,
                &mut bytes,
                prost::encoding::DecodeContext::default(),
            )?;
        }
    }
    Ok(())
}

pub fn decode_result(bytes: &[u8]) -> Result<p::Result> {
    ensure!(
        !bytes.is_empty() && bytes.len() <= 1 << 20,
        "TYPESCRIPT_RESULT_BUDGET"
    );
    let seen = fields(bytes, mask(&[1, 2, 3, 4, 10, 11, 12]), |tag, value| {
        match tag {
            3 => {
                fields(value, ((1u64 << 14) - 1) & !1, |_, _| Ok(()))?;
                scalars(
                    value,
                    &[
                        (1, u32::MAX as u64),
                        (2, u32::MAX as u64),
                        (3, u32::MAX as u64),
                        (4, u32::MAX as u64),
                        (5, u32::MAX as u64),
                    ],
                )?;
            }
            4 => {
                let receipt = fields(value, mask(&[1, 2, 3, 4]), |tag, value| {
                    if tag == 2 {
                        fields(value, mask(&[1, 2, 3, 4, 5]), |tag, value| {
                            if tag == 5 {
                                fields(value, mask(&[1, 2, 3, 4, 5]), |_, _| Ok(()))?;
                            }
                            Ok(())
                        })?;
                        scalars(value, &[(4, 4096)])?;
                    }
                    Ok(())
                })?;
                ensure!(receipt & (1 << 4) != 0, "TYPESCRIPT_USAGE_KNOWN_REQUIRED");
                scalars(value, &[(4, 1)])?;
            }
            10 | 11 => {
                fields(value, mask(&[1]), |_, archive| {
                    fields(archive, mask(&[1, 2, 3, 4]), |_, _| Ok(()))?;
                    Ok(())
                })?;
            }
            12 => {
                fields(value, mask(&[1, 2]), |_, _| Ok(()))?;
                scalars(value, &[(1, u32::MAX as u64)])?;
            }
            _ => {}
        }
        Ok(())
    })?;
    ensure!(
        (seen & mask(&[10, 11, 12])).count_ones() == 1,
        "TYPESCRIPT_RESULT_OUTCOME"
    );
    ensure!(
        seen & mask(&[1, 3, 4]) == mask(&[1, 3, 4]),
        "TYPESCRIPT_RESULT_REQUIRED"
    );
    scalars(bytes, &[(1, 1)])?;
    let result = p::Result::decode(bytes)?;
    ensure!(result.version == 1, "TYPESCRIPT_RESULT_VERSION");
    Ok(result)
}

#[cfg(test)]
mod tests {
    use super::*;
    #[test]
    fn duplicate_outcomes_and_non_boolean_certification_are_rejected_before_decode() {
        for data in [
            vec![8, 1, 26, 0, 34, 2, 32, 1, 82, 0, 98, 0],
            vec![8, 1, 26, 0, 34, 2, 32, 2, 98, 0],
        ] {
            assert!(p::Result::decode(data.as_slice()).is_ok());
            assert!(decode_result(&data).is_err());
        }
    }
    #[test]
    fn old_missing_and_truncated_results_are_rejected() {
        for data in [
            vec![],
            vec![8, 0],
            vec![8, 1, 26, 0, 34, 0, 98, 0],
            vec![8, 1, 26, 0, 34, 2, 32, 1, 98],
        ] {
            assert!(decode_result(&data).is_err());
        }
    }
}
