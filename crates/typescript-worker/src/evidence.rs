//! Bounded inspection for explicit component/acceptance commands, not worker IPC.
use anyhow::{Context, Result};
use codeana_contracts::graph::{Row, Table, cell};
use codeana_model::AnalysisContext;
use prost::Message;
use serde_json::{Value, json};
#[derive(Default)]
pub struct SemanticEvidence {
    pub declarations: Vec<Value>,
    pub calls: Vec<Value>,
    pub partial: bool,
    pub coverage_seen: u64,
}
fn text(row: &Row, index: usize) -> Result<&str> {
    match row.cells.get(index).and_then(|cell| cell.value.as_ref()) {
        Some(cell::Value::Text(value)) => Ok(value),
        _ => anyhow::bail!("SEMANTIC_EVIDENCE_COLUMN: {index}"),
    }
}
impl SemanticEvidence {
    pub fn record(&mut self, row: &Row, budget: &AnalysisContext) -> Result<()> {
        let kind = Table::try_from(row.table)?;
        if kind == Table::Coverage {
            budget.charge(1, 0, 0)?;
            self.coverage_seen += 1;
            self.partial |= text(row, 6)? != "complete";
        }
        if kind == Table::Node || (kind == Table::Edge && text(row, 6)? == "CALLS") {
            budget.charge(1, 32, (row.encoded_len() as u64) * 16 + 4096)?;
            if kind == Table::Node {
                self.declarations.push(json!({"id":text(row,0)?,"name":text(row,7)?,"kind":text(row,8)?,"language":text(row,3)?,"source":text(row,12)?,"metadata":text(row,21)?}));
            } else {
                let target = match row.cells.get(1).and_then(|cell| cell.value.as_ref()) {
                    Some(cell::Value::Text(value)) => Some(value),
                    None => None,
                    _ => anyhow::bail!("SEMANTIC_EVIDENCE_TARGET"),
                };
                self.calls.push(json!({"caller":text(row,0)?,"target":target,"language":text(row,9)?,"source":text(row,16)?,"location":text(row,17)?,"metadata":text(row,25)?}));
            }
        }
        Ok(())
    }
    pub fn verify_greet_fixture(&self) -> Result<()> {
        let declarations = self
            .declarations
            .iter()
            .filter(|row| row["name"] == "greet" && row["kind"] == "function")
            .collect::<Vec<_>>();
        anyhow::ensure!(
            declarations.len() == 1 && self.calls.len() == 2,
            "SEMANTIC_FIXTURE_COUNTS"
        );
        let id = declarations[0]["id"]
            .as_str()
            .context("SEMANTIC_FIXTURE_ID")?;
        for call in &self.calls {
            anyhow::ensure!(call["target"] == id, "SEMANTIC_FIXTURE_TARGET");
            let metadata: Value = serde_json::from_str(
                call["metadata"]
                    .as_str()
                    .context("SEMANTIC_FIXTURE_METADATA")?,
            )?;
            anyhow::ensure!(
                metadata["target_kind"] == "implementation",
                "SEMANTIC_FIXTURE_IMPLEMENTATION"
            );
        }
        Ok(())
    }
}
