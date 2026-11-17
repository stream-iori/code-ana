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

// Fixed-depth inventory admission. Repeated messages are bounded before prost
// allocates them; generated protobuf remains the only decoder.
fn inventory_fields(
    bytes: &[u8],
    allowed: u64,
    repeated: u64,
    remaining: &mut usize,
    mut nested: impl FnMut(u64, &[u8], &mut usize) -> Result<()>,
) -> Result<u64> {
    let mut seen = 0;
    crate::wire_fields(bytes, |tag, value| {
        ensure!(*remaining > 0, "TYPESCRIPT_INVENTORY_VALUES");
        *remaining -= 1;
        ensure!(
            tag < 64 && allowed & (1 << tag) != 0,
            "TYPESCRIPT_INVENTORY_FIELD"
        );
        let bit = 1 << tag;
        ensure!(
            seen & bit == 0 || repeated & bit != 0,
            "TYPESCRIPT_INVENTORY_DUPLICATE"
        );
        seen |= bit;
        nested(tag, value, remaining)
    })?;
    Ok(seen)
}

fn inventory_languages(mut bytes: &[u8]) -> Result<()> {
    use prost::encoding::{DecodeContext, WireType, decode_key, decode_varint, skip_field};
    while !bytes.is_empty() {
        let (tag, kind) = decode_key(&mut bytes)?;
        if tag != 4 {
            skip_field(kind, tag, &mut bytes, DecodeContext::default())?;
            continue;
        }
        match kind {
            WireType::Varint => ensure!(
                matches!(decode_varint(&mut bytes)?, 1 | 2),
                "TYPESCRIPT_INVENTORY_LANGUAGE"
            ),
            WireType::LengthDelimited => {
                let length = usize::try_from(decode_varint(&mut bytes)?)?;
                ensure!(length <= bytes.len(), "TYPESCRIPT_INVENTORY_LANGUAGE");
                let (mut packed, rest) = bytes.split_at(length);
                while !packed.is_empty() {
                    ensure!(
                        matches!(decode_varint(&mut packed)?, 1 | 2),
                        "TYPESCRIPT_INVENTORY_LANGUAGE"
                    );
                }
                bytes = rest;
            }
            _ => anyhow::bail!("TYPESCRIPT_INVENTORY_LANGUAGE"),
        }
    }
    Ok(())
}

pub fn decode_inventory(bytes: &[u8]) -> Result<p::PreparationInventory> {
    ensure!(
        !bytes.is_empty() && bytes.len() <= 32 << 20,
        "TYPESCRIPT_INVENTORY_BYTES"
    );
    let mut remaining = 1_000_000;
    let seen = inventory_fields(
        bytes,
        mask(&[1, 2]),
        mask(&[2]),
        &mut remaining,
        |tag, value, remaining| {
            if tag == 1 {
                let context = inventory_fields(
                    value,
                    mask(&[1, 2, 3, 4, 6, 7, 8, 9, 10, 11]),
                    mask(&[4, 6, 7, 8, 9, 10]),
                    remaining,
                    |tag, value, remaining| {
                        match tag {
                            4 => {
                                // Generated Go encodes repeated enum values as packed.
                                // Both wire forms are range-checked below.
                                let mut packed = value;
                                while !packed.is_empty() {
                                    ensure!(*remaining > 0, "TYPESCRIPT_INVENTORY_VALUES");
                                    *remaining -= 1;
                                    ensure!(
                                        matches!(
                                            prost::encoding::decode_varint(&mut packed)?,
                                            1 | 2
                                        ),
                                        "TYPESCRIPT_INVENTORY_LANGUAGE"
                                    );
                                }
                            }
                            6 => {
                                let seen = inventory_fields(
                                    value,
                                    mask(&[1, 2, 3, 4]),
                                    mask(&[2, 3]),
                                    remaining,
                                    |_, _, _| Ok(()),
                                )?;
                                ensure!(seen & (1 << 1) != 0, "TYPESCRIPT_DIRECTORY_PATH");
                                scalars(value, &[(4, 1)])?;
                            }
                            7 => {
                                let seen = inventory_fields(
                                    value,
                                    mask(&[1, 2, 3]),
                                    0,
                                    remaining,
                                    |_, _, _| Ok(()),
                                )?;
                                ensure!(seen == mask(&[1, 2, 3]), "TYPESCRIPT_LINK_FIELDS");
                            }
                            8 => {
                                let seen = inventory_fields(
                                    value,
                                    mask(&[1, 2]),
                                    0,
                                    remaining,
                                    |_, _, _| Ok(()),
                                )?;
                                ensure!(seen == mask(&[1, 2]), "TYPESCRIPT_LOOKUP_FIELDS");
                                scalars(value, &[(2, 2)])?;
                            }
                            _ => {}
                        }
                        Ok(())
                    },
                )?;
                ensure!(
                    context & mask(&[1, 2, 3, 4, 9, 11]) == mask(&[1, 2, 3, 4, 9, 11]),
                    "TYPESCRIPT_CONTEXT_FIELDS"
                );
                inventory_languages(value)?;
                scalars(value, &[(11, 1)])?;
            } else {
                let file = inventory_fields(
                    value,
                    mask(&[1, 2, 3, 4, 5, 6, 7, 8]),
                    0,
                    remaining,
                    |tag, value, _| {
                        if tag == 2 {
                            ensure!(value.len() == 64, "TYPESCRIPT_FILE_SHA");
                        }
                        Ok(())
                    },
                )?;
                ensure!(
                    file & mask(&[1, 2, 4]) == mask(&[1, 2, 4]),
                    "TYPESCRIPT_FILE_FIELDS"
                );
                scalars(value, &[(4, 3), (5, 2)])?;
            }
            Ok(())
        },
    )?;
    ensure!(seen == mask(&[1, 2]), "TYPESCRIPT_INVENTORY_FIELDS");
    Ok(p::PreparationInventory::decode(bytes)?)
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

#[cfg(test)]
mod inventory_tests {
    use super::*;
    fn valid() -> p::PreparationInventory {
        p::PreparationInventory {
            context: Some(p::ProjectContext {
                unit: "unit".into(),
                root: "/project".into(),
                config_path: "/project/tsconfig.json".into(),
                publish_languages: vec![2],
                root_files: vec!["/project/a.ts".into()],
                case_sensitive: Some(false),
                ..Default::default()
            }),
            files: vec![p::FileIdentity {
                path: "/project/a.ts".into(),
                sha256: "a".repeat(64),
                role: 1,
                language: 2,
                ..Default::default()
            }],
        }
    }
    #[test]
    fn inventory_repeated_entries_are_bounded_and_singular_fields_cannot_be_overwritten() {
        let original = valid();
        let bytes = original.encode_to_vec();
        assert_eq!(decode_inventory(&bytes).unwrap(), original);
        let mut duplicate = bytes.clone();
        duplicate.extend_from_slice(&bytes);
        assert!(decode_inventory(&duplicate).is_err());
        assert!(decode_inventory(&[0x0a, 0x00, 0x12, 0x00]).is_err());
        let mut missing = original;
        missing.context.as_mut().unwrap().case_sensitive = None;
        assert!(decode_inventory(&missing.encode_to_vec()).is_err());
    }
    #[test]
    fn inventory_enums_and_explicit_booleans_are_checked_before_decode() {
        let mut unknown = valid();
        unknown.context.as_mut().unwrap().publish_languages = vec![99];
        assert!(decode_inventory(&unknown.encode_to_vec()).is_err());
        unknown = valid();
        unknown.files[0].role = 99;
        assert!(decode_inventory(&unknown.encode_to_vec()).is_err());
        // A large unpacked enum must not truncate to an accepted i32 value.
        let mut context = valid().context.unwrap().encode_to_vec();
        context.extend_from_slice(&[0x20, 0x82, 0x80, 0x80, 0x80, 0x10]);
        assert!(inventory_languages(&context).is_err());
        context = valid().context.unwrap().encode_to_vec();
        context.extend_from_slice(&[0x58, 0x02]);
        let mut bytes = vec![0x0a];
        prost::encoding::encode_varint(context.len() as u64, &mut bytes);
        bytes.extend(context);
        let file = valid().files.pop().unwrap().encode_to_vec();
        bytes.push(0x12);
        prost::encoding::encode_varint(file.len() as u64, &mut bytes);
        bytes.extend(file);
        assert!(decode_inventory(&bytes).is_err());
    }
}
