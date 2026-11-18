//! Translate the private compiler inventory at its worker boundary. Domain and
//! configuration code depend only on model::typescript, never on engine DTOs.
use anyhow::{Context, Result};
use codeana_contracts::protobuf::typescript::v1 as p;
use codeana_model::{SourceLanguage, typescript as m};

fn language(raw: i32) -> Result<Option<SourceLanguage>> {
    Ok(match p::SourceLanguage::try_from(raw)? {
        p::SourceLanguage::Unspecified => None,
        p::SourceLanguage::Javascript => Some(SourceLanguage::JavaScript),
        p::SourceLanguage::Typescript => Some(SourceLanguage::TypeScript),
    })
}
fn optional(value: String) -> Option<String> {
    if value.is_empty() { None } else { Some(value) }
}

pub fn from_inventory(
    inventory: p::PreparationInventory,
    compiler: m::CompilerIdentity,
) -> Result<m::Inputs> {
    let context = inventory.context.context("TYPESCRIPT_INVENTORY_CONTEXT")?;
    let publish_languages = context
        .publish_languages
        .into_iter()
        .map(|raw| language(raw)?.context("TYPESCRIPT_PUBLICATION_LANGUAGE"))
        .collect::<Result<Vec<_>>>()?;
    let files = inventory
        .files
        .into_iter()
        .map(|file| {
            Ok(m::InputFile {
                path: file.path,
                sha256: file.sha256,
                bytes: file.size,
                role: match p::FileRole::try_from(file.role)? {
                    p::FileRole::Publish => m::FileRole::Publish,
                    p::FileRole::Auxiliary => m::FileRole::Auxiliary,
                    p::FileRole::Control => m::FileRole::Control,
                    p::FileRole::Unspecified => anyhow::bail!("TYPESCRIPT_INPUT_ROLE"),
                },
                language: language(file.language)?,
                module: optional(file.module),
                scope: optional(file.scope),
                world: optional(file.world).map(Into::into),
            })
        })
        .collect::<Result<Vec<_>>>()?;
    let absent = context
        .absent
        .into_iter()
        .map(|entry| {
            Ok(m::AbsentLookup {
                path: entry.path,
                kind: match p::LookupKind::try_from(entry.kind)? {
                    p::LookupKind::File => m::LookupKind::File,
                    p::LookupKind::Directory => m::LookupKind::Directory,
                    p::LookupKind::Unspecified => anyhow::bail!("TYPESCRIPT_LOOKUP_KIND"),
                },
            })
        })
        .collect::<Result<Vec<_>>>()?;
    let inputs = m::Inputs {
        schema_version: m::INPUT_SCHEMA,
        compiler,
        contexts: vec![m::Context {
            unit: context.unit.into(),
            root: context.root,
            config_path: context.config_path,
            publish_languages,
            case_sensitive: context
                .case_sensitive
                .context("TYPESCRIPT_CASE_RULES_REQUIRED")?,
            root_files: context.root_files,
            references: context.references,
            files,
            directories: context
                .directories
                .into_iter()
                .map(|entry| m::Directory {
                    path: entry.path,
                    files: entry.files,
                    directories: entry.directories,
                    listing_complete: entry.listing_complete,
                })
                .collect(),
            links: context
                .links
                .into_iter()
                .map(|entry| m::SymbolicLink {
                    path: entry.path,
                    target: entry.target,
                    allowed_root: entry.allowed_root,
                })
                .collect(),
            absent,
        }],
    };
    inputs.validate().map_err(anyhow::Error::msg)?;
    Ok(inputs)
}

#[cfg(test)]
pub(crate) mod tests {
    use super::*;
    pub(crate) fn fixture() -> (p::PreparationInventory, m::CompilerIdentity) {
        let compiler = m::CompilerIdentity {
            adapter: "test".into(),
            upstream_commit: "a".repeat(40),
            typescript_commit: "b".repeat(40),
            compiler_version: "7.1.0-dev".into(),
            go_version: "go1.26.2".into(),
            library_sha256: "a".repeat(64),
            header_sha256: "b".repeat(64),
            standard_library_digest: "c".repeat(64),
            analysis_proto_sha256: "d".repeat(64),
            graph_proto_sha256: "e".repeat(64),
        };
        let inventory = p::PreparationInventory {
            context: Some(p::ProjectContext {
                unit: "typescript/test".into(),
                root: "/project".into(),
                config_path: "/project/tsconfig.json".into(),
                publish_languages: vec![p::SourceLanguage::Typescript as i32],
                root_files: vec!["/project/a.ts".into()],
                case_sensitive: Some(false),
                directories: vec![p::Directory {
                    path: "/project".into(),
                    files: vec!["a.ts".into()],
                    directories: vec![],
                    listing_complete: true,
                }],
                ..Default::default()
            }),
            files: vec![p::FileIdentity {
                path: "/project/a.ts".into(),
                sha256: "1".repeat(64),
                size: 20,
                role: p::FileRole::Publish as i32,
                language: p::SourceLanguage::Typescript as i32,
                module: "project".into(),
                scope: "MAIN".into(),
                world: "ts/main".into(),
            }],
        };
        (inventory, compiler)
    }
    #[test]
    fn private_inventory_becomes_strict_protobuf_free_native_inputs() {
        let (inventory, compiler) = fixture();
        let inputs = from_inventory(inventory, compiler).unwrap();
        assert!(!inputs.contexts[0].case_sensitive);
        assert_eq!(
            inputs.contexts[0].publish_languages,
            vec![SourceLanguage::TypeScript]
        );
        assert_eq!(inputs.contexts[0].files[0].role, m::FileRole::Publish);
        let json = serde_json::to_vec(&inputs).unwrap();
        let again: m::Inputs = serde_json::from_slice(&json).unwrap();
        assert_eq!(inputs.semantic_digest(), again.semantic_digest());
    }
    #[test]
    fn absent_case_unknown_tags_and_invalid_owners_do_not_get_defaults() {
        let edits: Vec<fn(&mut p::PreparationInventory)> = vec![
            |i| i.context = None,
            |i| i.context.as_mut().unwrap().case_sensitive = None,
            |i| i.context.as_mut().unwrap().publish_languages = vec![99],
            |i| i.context.as_mut().unwrap().publish_languages = vec![0],
            |i| i.files[0].role = 0,
            |i| i.files[0].language = 99,
            |i| i.files[0].world.clear(),
            |i| {
                i.context.as_mut().unwrap().absent.push(p::AbsentLookup {
                    path: "/project/missing".into(),
                    kind: 0,
                })
            },
        ];
        for edit in edits {
            let (mut inventory, compiler) = fixture();
            edit(&mut inventory);
            assert!(from_inventory(inventory, compiler).is_err());
        }
    }
}
