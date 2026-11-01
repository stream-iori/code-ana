//! Application-owned query options. Public comment fields are wired in C1.
use anyhow::Result;
use codeana_model::{CommentContext, CommentQuery, NormalizedCommentQuery, Request};

/// Keep current request validation and comment-default resolution at one application boundary.
/// Callers freeze the returned effective comment options, never the mutable project defaults.
pub(super) fn normalize(
    request: &Request,
    comments: Option<(CommentQuery, CommentContext)>,
) -> Result<Option<NormalizedCommentQuery>> {
    codeana_query::preflight(request)?;
    comments
        .map(|(query, defaults)| query.normalize(defaults).map_err(anyhow::Error::new))
        .transpose()
}

#[cfg(test)]
mod tests {
    use super::*;
    use codeana_model::{DomainError, ErrorCode, Operation};

    #[test]
    fn production_boundary_preserves_validation_error_identity() {
        let request = Request {
            operation: Operation::SearchText,
            text_context: 101,
            ..Default::default()
        };
        let error = normalize(&request, None).unwrap_err();
        assert_eq!(
            error.downcast_ref::<DomainError>().unwrap().code,
            ErrorCode::TextContextBudget
        );
    }

    #[test]
    fn effective_comment_options_are_frozen_independently_of_later_defaults() {
        let request = Request {
            operation: Operation::SearchCode,
            ..Default::default()
        };
        let frozen = normalize(
            &request,
            Some((
                CommentQuery {
                    text: "库存".into(),
                    context_before: Some(0),
                    ..Default::default()
                },
                CommentContext {
                    before: 3,
                    after: 8,
                },
            )),
        )
        .unwrap()
        .unwrap();
        let bytes = serde_json::to_vec(&frozen).unwrap();
        let changed = normalize(
            &request,
            Some((
                CommentQuery::default(),
                CommentContext {
                    before: 10,
                    after: 20,
                },
            )),
        )
        .unwrap()
        .unwrap();
        let replay: NormalizedCommentQuery = serde_json::from_slice(&bytes).unwrap();
        assert_eq!(
            replay.context,
            CommentContext {
                before: 0,
                after: 8
            }
        );
        assert_ne!(replay.context, changed.context);
        assert!(
            normalize(
                &request,
                Some((
                    CommentQuery::default(),
                    CommentContext {
                        before: 101,
                        after: 5
                    }
                ))
            )
            .is_err()
        );
    }
}
