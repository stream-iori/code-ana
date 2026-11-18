//! Both source tags share a backend vocabulary, with explicit SQL boundaries.
use super::*;
use codeana_model::typescript::semantics::{DeclarationKind, Visibility};

fn insert(
    db: &Connection,
    id: &str,
    language: &str,
    kind: &str,
    type_kind: Option<&str>,
    visibility: &str,
) -> rusqlite::Result<usize> {
    db.execute("INSERT INTO nodes(id,symbol_id,label,kind,qualified_name,source_file,module,scope,language,provider_id,producer_id,declaration_kind,type_kind,visibility,modifiers,declared_modifiers,implicit_modifiers,nesting_depth,direct_member,synthetic,binding_resolved) VALUES(?1,?1,?1,?2,?1,'fixture.ts','app','MAIN',?3,'typescript-core','typescript-core',?2,?4,?5,'[]','[]','[]',0,0,0,1)",params![id,kind,language,type_kind,visibility])
}
#[test]
fn script_vocabulary_round_trips_through_compact_views_without_widening_java() -> Result<()> {
    let db = Connection::open_in_memory()?;
    graph_store::initialize(&db)?;
    for language in ["javascript", "typescript"] {
        for kind in DeclarationKind::ALL {
            for visibility in Visibility::ALL {
                let id = format!("{language}.{}.{}", kind.as_str(), visibility.as_str());
                insert(
                    &db,
                    &id,
                    language,
                    kind.as_str(),
                    kind.type_kind(),
                    visibility.as_str(),
                )?;
                let saved: (String, String, String) = db.query_row(
                    "SELECT declaration_kind,visibility,language FROM nodes WHERE id=?",
                    [&id],
                    |r| Ok((r.get(0)?, r.get(1)?, r.get(2)?)),
                )?;
                assert_eq!(
                    saved,
                    (
                        kind.as_str().into(),
                        visibility.as_str().into(),
                        language.into()
                    )
                );
            }
        }
    }
    assert!(insert(&db, "java-type-alias", "java", "type_alias", None, "public").is_err());
    assert!(insert(&db, "java-local", "java", "method", None, "local").is_err());
    assert!(insert(&db, "unsupported", "typescript", "invented", None, "public").is_err());
    assert!(
        insert(
            &db,
            "bad-visibility",
            "javascript",
            "function",
            None,
            "package-private"
        )
        .is_err()
    );
    assert_eq!(
        db.query_row("PRAGMA user_version", [], |r| r.get::<_, i64>(0))?,
        18
    );
    assert_eq!(
        db.query_row(
            "SELECT value FROM project_meta WHERE key='schema_version'",
            [],
            |r| r.get::<_, String>(0)
        )?,
        "37"
    );
    Ok(())
}
