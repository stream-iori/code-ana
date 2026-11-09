//! Final response sinks. Export is an atomic no-clobber write, never an implicit replay.
use anyhow::{Context, Result};
use codeana_model::*;
use std::{io::Write, path::Path};

pub fn preflight(path: Option<&Path>) -> Result<()> {
    if let Some(path) = path {
        anyhow::ensure!(
            !path.exists(),
            DomainError::new(
                ErrorCode::ExportPathExistsOrUnavailable,
                path.display().to_string()
            )
        );
        let parent = path
            .parent()
            .filter(|p| !p.as_os_str().is_empty())
            .unwrap_or(Path::new("."));
        anyhow::ensure!(
            parent.is_dir(),
            DomainError::new(
                ErrorCode::ExportPathExistsOrUnavailable,
                parent.display().to_string()
            )
        );
    }
    Ok(())
}

pub fn emit(
    request: &Request,
    result: &TaskResult,
    bytes: &[u8],
    export: Option<&Path>,
) -> Result<i32> {
    if let Some(path) = export {
        let receipt = codeana_application::presentation::export_receipt(request, result, path)?;
        let limit = if request.max_output_bytes == 0 {
            32768
        } else {
            request.max_output_bytes as usize
        };
        anyhow::ensure!(
            receipt.len() <= limit,
            DomainError::new(
                ErrorCode::ExportBudget,
                "export receipt exceeds --max-output-bytes; response file was not written"
            )
        );
        if let Err(error) = codeana_application::presentation::export_file(path, bytes) {
            let mut receipt = result.clone();
            if !receipt.output.cursor.is_empty() {
                receipt.output.records.clear();
            }
            receipt.output.delivery.state = DeliveryState::Failed;
            receipt.code = "EXPORT_WRITE_FAILED".into();
            receipt.message = error.to_string();
            receipt.exit_code = 1;
            let bytes = codeana_application::render_response(request, &mut receipt)?;
            std::io::stdout().write_all(&bytes)?;
            return Ok(1);
        }
        std::io::stdout().write_all(&receipt)?;
    } else {
        std::io::stdout()
            .write_all(bytes)
            .context("stdout response write failed")?;
    }
    Ok(result.exit_code)
}

/// Detect a closed downstream pipe without emitting an incomplete response.
pub fn ensure_open() -> Result<()> {
    let mut fd = libc::pollfd {
        fd: libc::STDOUT_FILENO,
        events: libc::POLLOUT,
        revents: 0,
    };
    let status = unsafe { libc::poll(&mut fd, 1, 0) };
    anyhow::ensure!(status >= 0, "stdout availability check failed");
    anyhow::ensure!(
        fd.revents & (libc::POLLERR | libc::POLLHUP | libc::POLLNVAL) == 0,
        "stdout response pipe closed"
    );
    Ok(())
}

#[cfg(test)]
mod tests {
    use super::*;
    #[test]
    fn an_oversized_export_receipt_is_refused_before_file_publication() {
        let request = Request {
            max_output_bytes: 2048,
            delivery: DeliveryOptions {
                format: Some(OutputFormat::Json),
                ..Default::default()
            },
            ..Default::default()
        };
        let mut result = TaskResult::success(Output {
            evidence: Evidence::complete(),
            ..Default::default()
        });
        result.publication = PublicationState::Published;
        let body = codeana_application::render_response(&request, &mut result).unwrap();
        let path = std::env::temp_dir().join("a".repeat(3000));
        let error = emit(&request, &result, &body, Some(&path)).unwrap_err();
        assert!(error.to_string().contains("EXPORT_BUDGET"));
        assert!(!path.exists());
        assert_eq!(result.publication, PublicationState::Published);
    }
}
