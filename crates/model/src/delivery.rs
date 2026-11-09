//! Presentation choices and delivery facts, independent of analysis and publication.
use serde::{Deserialize, Serialize};
use std::{fmt, str::FromStr};

pub const PUBLIC_PROJECTION_VERSION: u32 = 3;

#[derive(Debug, Clone, Copy, Default, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "snake_case")]
pub enum OutputFormat {
    #[default]
    Text,
    Json,
}
impl fmt::Display for OutputFormat {
    fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
        f.write_str(match self {
            Self::Text => "text",
            Self::Json => "json",
        })
    }
}
impl FromStr for OutputFormat {
    type Err = crate::DomainError;
    fn from_str(value: &str) -> Result<Self, Self::Err> {
        match value {
            "text" => Ok(Self::Text),
            "json" => Ok(Self::Json),
            _ => Err(crate::DomainError::new(
                crate::ErrorCode::InvalidOutputFormat,
                value,
            )),
        }
    }
}

#[derive(Debug, Clone, Default, PartialEq, Eq, Serialize, Deserialize)]
#[serde(default)]
pub struct DeliveryOptions {
    pub format: Option<OutputFormat>,
    pub detail: Option<bool>,
    /// Explicit CLI fields, used only to validate restoration of a frozen request.
    pub supplied: Vec<String>,
    pub project_explicit: bool,
}
impl DeliveryOptions {
    pub fn format(&self) -> OutputFormat {
        self.format.unwrap_or_default()
    }
    pub fn detail(&self) -> bool {
        self.detail.unwrap_or(false)
    }
    pub fn is_default(&self) -> bool {
        self == &Self::default()
    }
    pub fn normalize_identity(&mut self) {
        self.format = Some(self.format());
        self.detail = Some(self.detail());
        self.clear_presence();
    }
    pub fn clear_presence(&mut self) {
        self.supplied.clear();
        self.project_explicit = false;
    }
}

#[derive(Debug, Clone, Copy, Default, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "snake_case")]
pub enum DeliveryState {
    #[default]
    Complete,
    Page,
    Partial,
    Failed,
}

#[derive(Debug, Clone, Default, PartialEq, Eq, Serialize, Deserialize)]
#[serde(default)]
pub struct DeliveryStatus {
    pub context: DeliveryContext,
    pub state: DeliveryState,
    pub projection_version: u32,
    pub returned: usize,
    pub offset: usize,
    pub range_known: bool,
    pub total: Option<usize>,
    pub strict_rejected: bool,
}
#[derive(Debug, Clone, Default, PartialEq, Eq, Serialize, Deserialize)]
#[serde(default)]
pub struct DeliveryContext {
    pub format: OutputFormat,
    pub detail: bool,
    pub operation: crate::Operation,
    pub max_output_bytes: u64,
    pub historical: bool,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub comparison: Option<ComparisonContext>,
}
#[derive(Debug, Clone, Default, PartialEq, Eq, Serialize, Deserialize)]
pub struct ComparisonContext {
    pub section: String,
    pub base: String,
    pub target: String,
    pub base_kind: String,
    pub target_kind: String,
    pub base_identity: String,
    pub target_identity: String,
}
impl DeliveryStatus {
    pub fn is_default(&self) -> bool {
        self == &Self::default()
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    #[test]
    fn omission_is_distinct_from_an_explicit_format() {
        let omitted = DeliveryOptions::default();
        let explicit = DeliveryOptions {
            format: Some(OutputFormat::Text),
            ..Default::default()
        };
        assert_eq!(omitted.format(), explicit.format());
        assert_ne!(omitted, explicit);
        assert!("yaml".parse::<OutputFormat>().is_err());
    }
    #[test]
    fn explicit_defaults_have_the_same_request_identity() {
        let mut omitted = DeliveryOptions::default();
        let mut explicit = DeliveryOptions {
            format: Some(OutputFormat::Text),
            detail: Some(false),
            supplied: vec!["format".into()],
            ..Default::default()
        };
        omitted.normalize_identity();
        explicit.normalize_identity();
        assert_eq!(omitted, explicit);
    }
    #[test]
    fn old_delivery_metadata_can_be_read() {
        let value: DeliveryStatus = serde_json::from_str("{}").unwrap();
        assert_eq!(value.projection_version, 0);
        assert_eq!(value.state, DeliveryState::Complete);
    }
}
