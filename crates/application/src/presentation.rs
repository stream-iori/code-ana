//! Agent-facing projection and codecs. Business facts are read from typed records;
//! JSON values below are confined to the presentation boundary and operation display DTOs.
use anyhow::Result;
use codeana_model::*;
use serde::Serialize;
use serde_json::Value;
use std::fmt::Write;

pub fn scalar(value: &str) -> String {
    if value.is_empty()
        || value == "end"
        || value
            .chars()
            .any(|c| c.is_whitespace() || c.is_control() || matches!(c, '"' | '\\'))
    {
        serde_json::to_string(value).expect("string encoding")
    } else {
        value.to_owned()
    }
}

// Clean only a known DTO/attributes object. Values inside annotations and
// producer extensions are opaque: keys such as display_name/business_pk may
// themselves be business data there.
fn public_value(mut value: Value, _detail: bool) -> Value {
    if let Value::Object(fields) = &mut value {
        fields.retain(|key, _| {
            !matches!(
                key.as_str(),
                "display_id"
                    | "display_module"
                    | "display_name"
                    | "display_parent"
                    | "display_producer"
                    | "display_relation"
                    | "display_scope"
                    | "display_source"
                    | "display_target"
                    | "display_world"
                    | "call_site_pk"
                    | "comment_pk"
                    | "declaration_pk"
                    | "external_pk"
                    | "file_pk"
                    | "link_pk"
                    | "metadata_pk"
                    | "node_pk"
                    | "owner_pk"
                    | "site_pk"
                    | "string_pk"
                    | "value_pk"
            )
        });
    }
    value
}

fn compact_attributes(value: &mut Value) {
    if let Value::Object(fields) = value {
        // Relational projection aliases are not producer-specific evidence.
        for key in [
            "id",
            "kind",
            "label",
            "language_kind",
            "fact_hash",
            "stable_hash",
            "target_id",
            "fixture_signature",
            "fixture_span",
            "qualified_name",
            "symbol_id",
            "upstream_id",
            "producer_id",
            "provider_id",
            "schema_version",
            "source_file",
            "source_location",
            "source_ordinal",
            "begin_line",
            "begin_column",
            "end_line",
            "end_column",
            "declaration_begin_line",
            "declaration_begin_column",
            "declaration_end_line",
            "declaration_end_column",
            "declaration_source_location",
            "world",
            "module",
            "scope",
            "domain",
            "metadata",
        ] {
            fields.remove(key);
        }
        for key in [
            "declaration_kind",
            "declaration_namespace",
            "declared_modifiers",
            "declaring_type",
            "direct_member",
            "implicit_modifiers",
            "modifiers",
            "namespace",
            "nesting_depth",
            "package",
            "synthetic",
            "type_kind",
            "visibility",
            "binding_resolved",
            "receiver_static_type",
        ] {
            if fields.get(key).is_some_and(Value::is_null) {
                fields.remove(key);
            }
        }
        for key in [
            "package",
            "namespace",
            "receiver_static_type",
            "dispatch",
            "syntax_target",
        ] {
            if fields.get(key).and_then(Value::as_str) == Some("") {
                fields.remove(key);
            }
        }
    }
}

fn declaration_attributes(fact: &Fact) -> Result<Value> {
    declaration_attributes_for(fact.kind, &fact.signature, &fact.attributes)
}
fn declaration_attributes_for(
    kind: FactKind,
    signature: &str,
    attributes: &FactAttributes,
) -> Result<Value> {
    let mut value = public_value(serde_json::to_value(attributes)?, false);
    compact_attributes(&mut value);
    let Value::Object(fields) = &mut value else {
        return Ok(value);
    };
    for key in [
        "source_range",
        "element_kind",
        "entity_kind",
        "language",
        "upstream_producer",
    ] {
        fields.remove(key);
    }
    if kind == FactKind::Declaration
        && attributes
            .language
            .as_ref()
            .is_some_and(|language| language == "java")
    {
        for key in [
            "signature",
            "declaration_kind",
            "declaration_namespace",
            "declaring_type",
            "namespace",
            "package",
            "nesting_depth",
            "direct_member",
            "declared_modifiers",
            "implicit_modifiers",
        ] {
            fields.remove(key);
        }
        // Known Java predicates have an ordinary baseline. Keep nondefault
        // information, and leave unknown producer extensions untouched.
        for (key, modifier) in [
            ("isAbstract", "abstract"),
            ("isStatic", "static"),
            ("isInterface", "interface"),
            ("isConstructor", "constructor"),
        ] {
            if fields.get(key) == Some(&Value::Bool(false))
                || attributes.has_modifier(modifier)
                || attributes.element_kind().eq_ignore_ascii_case(modifier)
            {
                fields.remove(key);
            }
        }
        for key in ["isAccessor", "synthetic"] {
            if fields.get(key) == Some(&Value::Bool(false)) {
                fields.remove(key);
            }
        }
        if fields
            .get("visibility")
            .and_then(Value::as_str)
            .is_some_and(|visibility| attributes.has_modifier(visibility))
        {
            fields.remove("visibility");
        }
        if fields
            .get("type_kind")
            .and_then(Value::as_str)
            .is_some_and(|kind| attributes.element_kind().eq_ignore_ascii_case(kind))
        {
            fields.remove("type_kind");
        }
        if fields.get("binding_resolved") == Some(&Value::Bool(true)) {
            fields.remove("binding_resolved");
        }
        if let Some(Value::Array(parameters)) = fields.get_mut("parameters") {
            // Exact canonical argument types are already in the member selector.
            // Richer generic types (e.g. T versus erased Object) remain explicit.
            if let Some((_, args)) = signature.rsplit_once('(')
                && let Some(args) = args.strip_suffix(')')
            {
                let mut depth = 0usize;
                let mut start = 0;
                let mut types = Vec::new();
                for (i, ch) in args.char_indices() {
                    match ch {
                        '<' => depth += 1,
                        '>' => depth = depth.saturating_sub(1),
                        ',' if depth == 0 => {
                            types.push(args[start..i].trim());
                            start = i + 1;
                        }
                        _ => {}
                    }
                }
                if !args.is_empty() {
                    types.push(args[start..].trim());
                }
                if types.len() == parameters.len() {
                    for (parameter, canonical) in parameters.iter_mut().zip(types) {
                        if let Value::Object(parameter) = parameter
                            && parameter.get("type").and_then(Value::as_str) == Some(canonical)
                        {
                            parameter.remove("type");
                        }
                    }
                }
            }
            if !parameters.is_empty()
                && parameters.iter().all(|p| {
                    p.as_object().is_some_and(|p| {
                        p.len() == 1 && p.get("name").is_some_and(Value::is_string)
                    })
                })
            {
                let names = parameters
                    .iter()
                    .map(|p| scalar(p["name"].as_str().expect("name")))
                    .collect::<Vec<_>>()
                    .join(", ");
                fields.insert("parameter-names".into(), names.into());
                fields.remove("parameters");
            }
        }
        fields.retain(|_, value| !value.as_array().is_some_and(Vec::is_empty));
    }
    Ok(value)
}

pub fn public_record(record: &Record, detail: bool) -> Result<Value> {
    let mut value = public_value(record.wire()?, detail);
    if let Value::Object(fields) = &mut value {
        if let Record::Fact(row) = record {
            fields.remove("metadata");
            fields.insert(
                "attributes".into(),
                public_value(serde_json::to_value(&row.fact.attributes)?, detail),
            );
            if !row.call_targets.is_empty() {
                fields.insert(
                    "call_targets".into(),
                    Value::Array(
                        row.call_targets
                            .iter()
                            .map(|binding| public_record(&Record::from(binding.clone()), detail))
                            .collect::<Result<_>>()?,
                    ),
                );
            }
        }
        if !detail {
            match record {
                Record::Source(_) | Record::Text(_) => {
                    fields.remove("hash");
                }
                Record::Project(_) => {
                    fields.remove("profile");
                }
                Record::Comment(_) => {
                    if let Some(Value::Object(comment)) = fields.get_mut("comment") {
                        comment.remove("body_map");
                    }
                }
                Record::Fact(row) => {
                    fields.insert("attributes".into(), declaration_attributes(&row.fact)?);
                    if let Some(range) = row.fact.attributes.source_range.as_ref() {
                        fields.insert("range".into(), serde_json::to_value(range)?);
                    }
                    if !row.fact.signature.is_empty() {
                        fields.remove("name");
                    }
                    if let Some(Value::Object(owner)) = fields.get_mut("owner") {
                        owner.remove("producer");
                    }
                    if let Some(Value::Object(attrs)) = fields.get_mut("attributes") {
                        attrs.remove("upstream_producer");
                    }
                }
                _ => {}
            }
        }
    }
    Ok(value)
}

pub fn public_operation_record(request: &Request, record: &Record) -> Result<Value> {
    let mut value = public_record(record, request.delivery.detail())?;
    if request.operation == Operation::Diff
        && !request.delivery.detail()
        && matches!(record, Record::Display(_))
        && let super::history::DiffRecord::Fact(change) = serde_json::from_value(record.wire()?)?
        && let Some(Value::Object(fact)) = value.get_mut("fact")
    {
        fact.insert(
            "attributes".into(),
            declaration_attributes_for(
                change.fact.kind,
                &change.fact.signature,
                &change.fact.attributes,
            )?,
        );
        if !change.fact.signature.is_empty() {
            fact.remove("name");
        }
        if let Some(range) = change.fact.attributes.source_range.as_ref() {
            fact.insert("range".into(), serde_json::to_value(range)?);
        }
    }
    if request.operation == Operation::Report
        && let Some(Value::Array(diagnostics)) = value.get_mut("diagnostics")
    {
        for diagnostic in diagnostics {
            let fact: Fact = serde_json::from_value(diagnostic.clone())?;
            *diagnostic = public_record(&Record::from(fact), request.delivery.detail())?;
        }
    }
    Ok(value)
}
pub fn comparison_header(context: Option<&ComparisonContext>) -> String {
    let Some(context) = context else {
        return String::new();
    };
    let endpoint = |label: &str, kind: &str, identity: &str| {
        if identity.is_empty() {
            format!("{} (unknown)", scalar(label))
        } else {
            format!("{} ({} {})", scalar(label), scalar(kind), scalar(identity))
        }
    };
    format!(
        "diff: {}\nbase: {}\ntarget: {}\n",
        scalar(&context.section),
        endpoint(&context.base, &context.base_kind, &context.base_identity),
        endpoint(
            &context.target,
            &context.target_kind,
            &context.target_identity
        )
    )
}
fn primary_extras(kind: FactKind, attributes: &mut Value) -> String {
    let mut extras = String::new();
    if kind == FactKind::Declaration
        && let Value::Object(fields) = attributes
    {
        if fields.get("returnType").is_some_and(Value::is_string)
            && let Some(Value::String(return_type)) = fields.remove("returnType")
        {
            let _ = write!(extras, " -> {}", scalar(&return_type));
        }
        if let Some(Value::Array(modifiers)) = fields.remove("modifiers") {
            let modifiers = modifiers.iter().map(atom).collect::<Vec<_>>().join(" ");
            if !modifiers.is_empty() {
                let _ = write!(extras, " [{modifiers}]");
            }
        }
    }
    extras
}
fn diff_record_text(
    out: &mut String,
    record: &Record,
    detail: bool,
    world_group: &mut String,
) -> Result<()> {
    use super::history::DiffRecord;
    let row: DiffRecord = serde_json::from_value(record.wire()?)?;
    match row {
        DiffRecord::File(file) => {
            let _ = writeln!(out, "{} {}", scalar(&file.change), scalar(&file.path));
            if detail {
                if let Some(hash) = file.base {
                    let _ = writeln!(out, "  base-hash: {}", scalar(&hash));
                }
                if let Some(hash) = file.target {
                    let _ = writeln!(out, "  target-hash: {}", scalar(&hash));
                }
            }
        }
        DiffRecord::Fact(change) => {
            let f = &change.fact;
            let signature = if f.signature.is_empty() {
                &f.name
            } else {
                &f.signature
            };
            if world_group != f.world.as_str() {
                *world_group = f.world.to_string();
                if !world_group.is_empty() {
                    let _ = writeln!(out, "world: {}", scalar(world_group));
                }
            }
            let mut attrs = if detail {
                public_value(serde_json::to_value(&f.attributes)?, true)
            } else {
                declaration_attributes_for(f.kind, &f.signature, &f.attributes)?
            };
            let extras = if detail {
                String::new()
            } else {
                primary_extras(f.kind, &mut attrs)
            };
            let _ = writeln!(
                out,
                "  {} {}{}{} count:{}→{}",
                f.kind.name(),
                scalar(signature),
                extras,
                position(f.attributes.source_range.as_ref()),
                change.base_count,
                change.target_count
            );
            if !f.parent.is_empty() {
                let _ = writeln!(out, "    parent: {}", scalar(&f.parent));
            }
            if !f.target.is_empty() {
                let _ = writeln!(out, "    target: {}", scalar(&f.target));
            }
            if !f.relation.is_empty() {
                let _ = writeln!(out, "    relation: {}", scalar(&f.relation));
            }
            if f.status != FactStatus::Unspecified {
                let _ = writeln!(out, "    status: {}", f.status);
            }
            if !f.evidence_complete {
                out.push_str("    evidence: partial\n");
            }
            if !f.declaration_resolved {
                out.push_str("    identity: unresolved\n");
            }
            value_text(out, &attrs, 4);
        }
        DiffRecord::Impact(impact) => {
            let _ = writeln!(
                out,
                "{} changed {}",
                scalar(&impact.side),
                scalar(&impact.changed)
            );
            let _ = writeln!(out, "  possible: {}", impact.possible);
            out.push_str(&String::from_utf8(record_text(
                &Request::default(),
                Record::Call(impact.edge),
            )?)?);
        }
    }
    Ok(())
}

#[derive(Serialize)]
struct ResultFields<'a> {
    execution: ExecutionState,
    assessment: Assessment,
    gate: GateState,
    publication: PublicationState,
    report: &'a ReportState,
    revision: &'a str,
    producer: &'a str,
    code: &'a ErrorCode,
    message: &'a str,
    exit_code: i32,
}
#[derive(Serialize)]
struct JsonReceipt<'a> {
    records: Vec<Value>,
    result: ResultFields<'a>,
    evidence: &'a Evidence,
    delivery: JsonDelivery<'a>,
}
#[derive(Serialize)]
struct JsonDelivery<'a> {
    #[serde(flatten)]
    status: &'a DeliveryStatus,
    cursor: &'a str,
}

pub fn encode(request: &Request, result: &TaskResult) -> Result<Vec<u8>> {
    if request.delivery.format() == OutputFormat::Json {
        let response = JsonReceipt {
            records: result
                .output
                .records
                .iter()
                .map(|r| public_operation_record(request, r))
                .collect::<Result<_>>()?,
            result: ResultFields {
                execution: result.execution,
                assessment: result.assessment,
                gate: result.gate,
                publication: result.publication,
                report: &result.report,
                revision: &result.revision,
                producer: &result.producer,
                code: &result.code,
                message: &result.message,
                exit_code: result.exit_code,
            },
            evidence: &result.output.evidence,
            delivery: JsonDelivery {
                status: &result.output.delivery,
                cursor: &result.output.cursor,
            },
        };
        let mut encoded = serde_json::to_vec(&response)?;
        encoded.push(b'\n');
        return Ok(encoded);
    }
    text(request, result).map(String::into_bytes)
}

fn block(out: &mut String, path: &str, location: &str, language: &str, body: &str) {
    let _ = writeln!(out, "{}{}", scalar(path), location);
    let longest = body.split(|c| c != '`').map(str::len).max().unwrap_or(0);
    let fence = "`".repeat(longest.saturating_add(1).max(3));
    let _ = writeln!(out, "{fence}{language}");
    out.push_str(body);
    // Exactly one framing LF; a real trailing newline remains in body.
    out.push('\n');
    let _ = writeln!(out, "{fence}");
}

fn position(range: Option<&SourceRange>) -> String {
    let Some(r) = range else {
        return String::new();
    };
    match (r.begin_line, r.begin_column, r.end_line, r.end_column) {
        (Some(l), Some(c), Some(el), Some(ec)) => format!(" @{l}:{c}–{el}:{ec}"),
        (Some(l), Some(c), _, _) => format!(" @{l}:{c}"),
        _ => String::new(),
    }
}

fn call_sites(out: &mut String, call: &CallRelation) {
    let _ = writeln!(out, "    sites: {}", call.sites.len());
    let mut path = String::new();
    for id in &call.sites {
        if let Some(site) = call.locations.iter().find(|site| &site.id == id) {
            if path != site.path {
                path.clone_from(&site.path);
                let _ = writeln!(out, "      {}", scalar(&path));
            }
            let _ = writeln!(
                out,
                "        {} {}",
                position(site.range.as_ref()).trim(),
                scalar(id)
            );
        } else {
            let _ = writeln!(out, "      {}", scalar(id));
        }
    }
}

fn value_text(out: &mut String, value: &Value, indent: usize) {
    let pad = " ".repeat(indent);
    match value {
        Value::Object(fields) => {
            for (key, value) in fields {
                if value.is_object() || value.is_array() {
                    let _ = writeln!(out, "{pad}{}", scalar(key));
                    value_text(out, value, indent + 2);
                } else {
                    let _ = writeln!(out, "{pad}{}: {}", scalar(key), atom(value));
                }
            }
        }
        Value::Array(values) => {
            for value in values {
                if value.is_object() || value.is_array() {
                    let _ = writeln!(out, "{pad}-");
                    value_text(out, value, indent + 2);
                } else {
                    let _ = writeln!(out, "{pad}- {}", atom(value));
                }
            }
        }
        _ => {
            let _ = writeln!(out, "{pad}{}", atom(value));
        }
    }
}
fn atom(value: &Value) -> String {
    match value {
        Value::String(s) => scalar(s),
        other => other.to_string(),
    }
}

pub fn record_text(request: &Request, record: Record) -> Result<Vec<u8>> {
    let result = TaskResult::success(Output {
        records: vec![record],
        evidence: Evidence::complete(),
        ..Default::default()
    });
    text_body(request, &result, false).map(String::into_bytes)
}
fn text(request: &Request, result: &TaskResult) -> Result<String> {
    text_body(request, result, true)
}
fn text_body(request: &Request, result: &TaskResult, footer: bool) -> Result<String> {
    let mut out = comparison_header(result.output.delivery.context.comparison.as_ref());
    let mut source_group = String::new();
    let mut caller_group = String::new();
    let mut owner_group = String::new();
    let mut world_group = String::new();
    let mut records = result.output.records.iter().peekable();
    while let Some(record) = records.next() {
        match record {
            Record::Fact(row) => {
                let f = &row.fact;
                let world = f.owner.world.as_str();
                if world_group != world {
                    world_group = world.to_owned();
                    source_group.clear();
                    owner_group.clear();
                    if !world.is_empty() {
                        let _ = writeln!(out, "world: {}", scalar(world));
                    }
                }
                if f.source != source_group {
                    source_group = f.source.clone();
                    owner_group.clear();
                    if !f.source.is_empty() {
                        let _ = writeln!(out, "{}", scalar(&f.source));
                    }
                }
                let signature = if !f.signature.is_empty() {
                    &f.signature
                } else if !f.name.is_empty() {
                    &f.name
                } else {
                    f.id.as_str()
                };
                let (owner, member) = signature.split_once('#').unwrap_or(("", signature));
                if !owner.is_empty() && owner != owner_group {
                    owner_group = owner.to_owned();
                    let _ = writeln!(out, "  owner {}", scalar(owner));
                }
                let indent = if owner.is_empty() { "  " } else { "    " };
                let kind = if f.attributes.element_kind().is_empty() {
                    f.kind.name().to_owned()
                } else {
                    f.attributes.element_kind().to_lowercase()
                };
                let mut attributes = if request.delivery.detail() {
                    public_value(serde_json::to_value(&f.attributes)?, true)
                } else {
                    declaration_attributes(f)?
                };
                let extras = if request.delivery.detail() {
                    String::new()
                } else {
                    primary_extras(f.kind, &mut attributes)
                };
                let _ = writeln!(
                    out,
                    "{indent}{kind} {}{}{}",
                    scalar(member),
                    extras,
                    position(f.attributes.source_range.as_ref())
                );
                if signature != f.id.as_str()
                    && (matches!(f.kind, FactKind::CallSite | FactKind::Reference)
                        || !f.declaration_resolved
                        || request.delivery.detail())
                {
                    let _ = writeln!(out, "{indent}  id: {}", scalar(f.id.as_str()));
                }
                if f.kind == FactKind::CallSite {
                    if !f.parent.is_empty() {
                        let _ = writeln!(out, "{indent}  caller: {}", scalar(&f.parent));
                    }
                    if row.call_targets.is_empty() {
                        let _ = writeln!(out, "{indent}  binding: unknown");
                    }
                    for binding in &row.call_targets {
                        let _ = writeln!(
                            out,
                            "{indent}  target: {} ({})",
                            scalar(&binding.target),
                            binding.status
                        );
                        let mut proof = public_value(
                            serde_json::to_value(&binding.attributes)?,
                            request.delivery.detail(),
                        );
                        if let Value::Object(fields) = &mut proof {
                            fields.remove("source_range");
                        }
                        compact_attributes(&mut proof);
                        value_text(&mut out, &proof, indent.len() + 4);
                    }
                }
                if !f.target.is_empty() {
                    let _ = writeln!(out, "{indent}  target: {}", scalar(&f.target));
                }
                if !f.relation.is_empty() {
                    let _ = writeln!(out, "{indent}  relation: {}", scalar(&f.relation));
                }
                if !f.evidence_complete {
                    let _ = writeln!(out, "{indent}  evidence: partial");
                }
                if let Some(depth) = row.depth {
                    let _ = writeln!(out, "{indent}  depth: {depth}");
                }
                if let Some(runtime) = &row.runtime_implementation {
                    let _ = writeln!(
                        out,
                        "{indent}  instantiability: {}",
                        scalar(&runtime.instantiability)
                    );
                    let _ = writeln!(out, "{indent}  reason: {}", scalar(&runtime.reason));
                    for proof in &runtime.proof {
                        let _ = writeln!(out, "{indent}  proof: {}", scalar(proof));
                    }
                }
                for comment in &row.comment_hits {
                    let _ = writeln!(
                        out,
                        "{indent}  comment: {} @{}:{}",
                        scalar(comment.comment.id.as_str()),
                        comment.comment.range.begin_line,
                        comment.comment.range.begin_column
                    );
                }
                if let Value::Object(fields) = &mut attributes {
                    for key in ["source_range", "element_kind", "entity_kind", "metadata"] {
                        fields.remove(key);
                    }
                }
                if !request.delivery.detail() {
                    compact_attributes(&mut attributes);
                }
                value_text(&mut out, &attributes, indent.len() + 2);
            }
            Record::Source(s) => {
                let mut body = s.text.clone();
                let mut end = s.span.end;
                let mut range = s.range.clone();
                while let Some(Record::Source(next)) = records.peek() {
                    if next.path != s.path
                        || next.hash != s.hash
                        || next.span.start != end
                        || next.language != s.language
                    {
                        break;
                    }
                    body.push_str(&next.text);
                    end = next.span.end;
                    if let (Some(range), Some(next_range)) = (&mut range, &next.range) {
                        range.end_line = next_range.end_line;
                        range.end_column = next_range.end_column;
                    }
                    records.next();
                }
                block(
                    &mut out,
                    &s.path,
                    &position(range.as_ref()),
                    &s.language,
                    &body,
                );
            }
            Record::Call(call) => {
                if caller_group != call.from {
                    caller_group = call.from.clone();
                    let _ = writeln!(out, "{}", scalar(&call.from));
                }
                let _ = writeln!(out, "  {} {}", scalar(&call.kind), scalar(&call.to));
                if call.recursion.is_some() {
                    let _ = writeln!(out, "    recursion: direct");
                }
                call_sites(&mut out, call);
            }
            Record::Trace(path) => {
                let _ = writeln!(out, "{} → {}", scalar(&path.from), scalar(&path.to));
                for (i, node) in path.nodes.iter().enumerate() {
                    let _ = writeln!(out, "  {} {}", i + 1, scalar(node));
                }
                for hop in &path.hops {
                    let _ = writeln!(
                        out,
                        "  {} {} → {}",
                        scalar(&hop.kind),
                        scalar(&hop.from),
                        scalar(&hop.to)
                    );
                    call_sites(&mut out, hop);
                }
            }
            Record::Dispatch(candidate) => {
                let _ = writeln!(out, "call: {}", scalar(&candidate.call_site));
                if let Some(algorithm) = &candidate.algorithm {
                    let _ = writeln!(out, "  algorithm: {}", scalar(algorithm));
                }
                if let Some(world) = &candidate.analysis_world {
                    let _ = writeln!(out, "  analysis: {}", scalar(world));
                }
                if !candidate.world.is_empty() {
                    let _ = writeln!(out, "  world: {}", scalar(&candidate.world));
                }
                if let Some(origin) = &candidate.binding_origin {
                    let _ = writeln!(out, "  binding-origin: {}", scalar(origin));
                }
                if let Some(kind) = &candidate.candidate {
                    let _ = writeln!(out, "  candidate: {}", scalar(kind));
                }
                if let Some(executable) = candidate.executable {
                    let _ = writeln!(out, "  executable: {executable}");
                }
                if let Some(modeled) = &candidate.modeled {
                    value_text(&mut out, &serde_json::to_value(modeled)?, 2);
                }
                if let Some(binding) = &candidate.binding {
                    let _ = writeln!(out, "  binding: {binding}");
                }
                if let Some(target) = &candidate.target {
                    let _ = writeln!(
                        out,
                        "  {}: {}",
                        if candidate.possible {
                            "possible"
                        } else {
                            "target"
                        },
                        scalar(target)
                    );
                }
                for proof in candidate
                    .proof
                    .iter()
                    .chain(candidate.type_proof.iter().flatten())
                {
                    let _ = writeln!(out, "  proof: {}", scalar(proof));
                }
            }
            Record::Text(row) => {
                let mut lines = std::collections::BTreeMap::new();
                let mut hits = vec![row.line];
                let add = |lines: &mut std::collections::BTreeMap<usize, String>,
                           row: &TextMatch| {
                    for line in row
                        .context_before
                        .iter()
                        .chain(row.context_after.iter())
                        .flatten()
                    {
                        lines.insert(line.line, line.text.clone());
                    }
                    lines.insert(row.line, row.text.clone());
                };
                add(&mut lines, row);
                while let Some(Record::Text(next)) = records.peek() {
                    let begin = next
                        .context_before
                        .as_ref()
                        .and_then(|c| c.first())
                        .map_or(next.line, |l| l.line);
                    if next.path != row.path
                        || next.hash != row.hash
                        || begin > lines.last_key_value().map_or(0, |(l, _)| *l) + 1
                    {
                        break;
                    }
                    hits.push(next.line);
                    add(&mut lines, next);
                    records.next();
                }
                let begin = *lines.first_key_value().expect("match line").0;
                let end = *lines.last_key_value().expect("match line").0;
                let body = lines
                    .values()
                    .map(String::as_str)
                    .collect::<Vec<_>>()
                    .join("\n");
                let hits = hits
                    .iter()
                    .map(ToString::to_string)
                    .collect::<Vec<_>>()
                    .join(",");
                block(
                    &mut out,
                    &row.path,
                    &format!(" @{begin}–{end} matches:{hits}"),
                    "",
                    &body,
                );
            }
            Record::Comment(row) => {
                let c = &row.comment;
                block(
                    &mut out,
                    &c.source_file,
                    &format!(
                        " @{}:{}–{}:{}",
                        c.range.begin_line,
                        c.range.begin_column,
                        c.range.end_line,
                        c.range.end_column
                    ),
                    "",
                    &c.body,
                );
                for link in &row.links {
                    if let Some(id) = &link.declaration {
                        let _ =
                            writeln!(out, "  {}: {}", link.relation.as_str(), scalar(id.as_str()));
                    }
                }
                for hit in &row.hits {
                    let _ = writeln!(
                        out,
                        "  hit: {}:{}–{}:{}",
                        hit.source.begin_line,
                        hit.source.begin_column,
                        hit.source.end_line,
                        hit.source.end_column
                    );
                }
            }
            Record::Project(project) => {
                let _ = writeln!(out, "sources: {}", project.sources);
                for (kind, count) in &project.counts {
                    let _ = writeln!(out, "{kind}: {count}");
                }
            }
            Record::Group(group) => value_text(
                &mut out,
                &public_record(&Record::Group(group.clone()), request.delivery.detail())?,
                0,
            ),
            Record::Display(_) if request.operation == Operation::Diff => {
                diff_record_text(
                    &mut out,
                    record,
                    request.delivery.detail(),
                    &mut world_group,
                )?;
            }
            Record::Display(_) => {
                let mut value = public_operation_record(request, record)?;
                if request.operation == Operation::Index
                    && !request.delivery.detail()
                    && let Value::Object(fields) = &mut value
                {
                    for key in [
                        "revision",
                        "mode",
                        "facts",
                        "statistics",
                        "ack_warning",
                        "manifest",
                        "profile",
                        "producer",
                    ] {
                        fields.remove(key);
                    }
                }
                if !request.delivery.detail()
                    && let Value::Object(fields) = &mut value
                {
                    match request.operation {
                        Operation::Snapshot | Operation::SnapshotList => {
                            if let Some(Value::Object(manifest)) = fields.get("manifest")
                                && let Some(Value::Array(entries)) = manifest.get("files")
                            {
                                let count = entries
                                    .iter()
                                    .filter_map(|entry| entry.get("path").and_then(Value::as_str))
                                    .collect::<std::collections::BTreeSet<_>>()
                                    .len();
                                let instances = entries.len();
                                fields.insert("files".into(), count.into());
                                if count != instances {
                                    fields.insert("instances".into(), instances.into());
                                }
                            }
                            if fields.get("revision").and_then(Value::as_str) == Some("") {
                                fields.remove("revision");
                            }
                            for key in ["manifest", "effective", "profile", "project", "created"] {
                                fields.remove(key);
                            }
                        }
                        Operation::Doctor => {
                            for key in ["generation", "effective", "java_bundle"] {
                                fields.remove(key);
                            }
                        }
                        Operation::Report => {
                            for key in ["effective", "context", "schema_version", "level"] {
                                fields.remove(key);
                            }
                            if let Some(Value::Object(global)) = fields.get_mut("global") {
                                global.remove("next_steps");
                            }
                            if let Some(Value::Object(result)) = fields.get_mut("result") {
                                result.remove("output");
                                result.remove("revision");
                                result.remove("producer");
                            }
                        }
                        _ => {}
                    }
                }
                value_text(&mut out, &value, 0);
            }
        }
    }
    if !footer {
        return Ok(out);
    }
    if result.output.records.is_empty()
        && result.output.delivery.state == DeliveryState::Complete
        && result.output.evidence.enumeration
        && result.output.evidence.semantic
        && result.output.delivery.returned == 0
        && result.exit_code == 0
        && matches!(
            request.operation,
            Operation::SearchCode
                | Operation::SearchComments
                | Operation::SearchText
                | Operation::Resolve
        )
    {
        out.push_str("No matches.\n");
    }
    if matches!(request.operation, Operation::Index)
        || result.publication == PublicationState::Published
        || request.operation == Operation::Snapshot && request.name == "analysis"
    {
        let _ = writeln!(out, "index: {}", result.publication);
    }
    if matches!(result.gate, GateState::Rejected | GateState::Unknown) {
        let _ = writeln!(out, "gate: {}", result.gate);
    }
    let evidence = &result.output.evidence;
    if !evidence.semantic {
        let _ = writeln!(
            out,
            "semantic: {}",
            if result.assessment == Assessment::Unknown {
                "unknown"
            } else {
                "partial"
            }
        );
        for reason in &evidence.reasons {
            let _ = writeln!(out, "  {}", scalar(reason));
        }
    } else if !evidence.reasons.is_empty() {
        for reason in &evidence.reasons {
            let _ = writeln!(out, "reason: {}", scalar(reason));
        }
    }
    let limits: Vec<_> = evidence
        .limits
        .iter()
        .filter(|s| {
            !matches!(
                s.as_str(),
                "page" | "output_bytes" | "terminal_evidence_details"
            )
        })
        .collect();
    if !evidence.enumeration && (evidence.limits.is_empty() || !limits.is_empty()) {
        out.push_str(if evidence.limits.is_empty() {
            "computation: unknown\n"
        } else {
            "computation: limited\n"
        });
        for limit in limits {
            let _ = writeln!(out, "  {}", scalar(limit));
        }
    }
    let d = &result.output.delivery;
    if !result.output.cursor.is_empty() {
        if d.range_known && !matches!(d.state, DeliveryState::Partial | DeliveryState::Failed) {
            let _ = write!(out, "results: {}–{}", d.offset + 1, d.offset + d.returned);
            if let Some(total) = d.total {
                let _ = write!(out, " / {total}");
            }
            out.push('\n');
        }
        out.push_str("delivery: page\n");
        let _ = writeln!(out, "cursor: {}", scalar(&result.output.cursor));
    }
    if matches!(d.state, DeliveryState::Partial | DeliveryState::Failed) {
        let _ = writeln!(
            out,
            "delivery: {}",
            if d.state == DeliveryState::Partial {
                "partial"
            } else {
                "failed"
            }
        );
    }
    if matches!(result.report.as_str(), "pending" | "failed" | "unknown") {
        let _ = writeln!(out, "report: {}", result.report);
    }
    if result.exit_code != 0 {
        if result.execution == ExecutionState::Completed {
            out.push_str("execution: completed\n");
        }
        let _ = writeln!(out, "error: {}: {}", result.code, scalar(&result.message));
    }
    if request.delivery.detail() || !request.revision.is_empty() {
        if !result.revision.is_empty() {
            let _ = writeln!(out, "revision: {}", result.revision);
        }
        if !result.producer.is_empty() {
            let _ = writeln!(out, "producer: {}", scalar(&result.producer));
        }
    }
    out.push_str("end\n");
    Ok(out)
}

/// Stage in the destination directory, then atomically publish without clobbering.
pub fn export_file(path: &std::path::Path, bytes: &[u8]) -> Result<()> {
    use std::io::Write as _;
    let parent = path
        .parent()
        .filter(|p| !p.as_os_str().is_empty())
        .unwrap_or(std::path::Path::new("."));
    let mut file = tempfile::NamedTempFile::new_in(parent)?;
    file.write_all(bytes)?;
    file.as_file().sync_all()?;
    file.persist_noclobber(path).map_err(|e| {
        DomainError::new(
            ErrorCode::ExportPathExistsOrUnavailable,
            e.error.to_string(),
        )
    })?;
    std::fs::File::open(parent)?.sync_all()?;
    Ok(())
}

pub fn export_receipt(
    request: &Request,
    result: &TaskResult,
    path: &std::path::Path,
) -> Result<Vec<u8>> {
    let mut receipt = result.clone();
    receipt.output.records.clear();
    if request.delivery.format() == OutputFormat::Json {
        let mut value: Value = serde_json::from_slice(&encode(request, &receipt)?)?;
        value.as_object_mut().expect("receipt object").insert(
            "exported".into(),
            Value::String(path.to_string_lossy().into_owned()),
        );
        let mut bytes = serde_json::to_vec(&value)?;
        bytes.push(b'\n');
        Ok(bytes)
    } else {
        let mut bytes = format!("exported: {}\n", scalar(&path.to_string_lossy())).into_bytes();
        bytes.extend(encode(request, &receipt)?);
        Ok(bytes)
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    #[test]
    fn raw_data_cannot_fake_a_receipt_and_keeps_its_last_newline() {
        for body in ["end", "end\n", "```\r\n中😀\n", ""] {
            let mut encoded = String::new();
            block(&mut encoded, "end", " @1:1", "", body);
            let mut lines = encoded.splitn(3, '\n');
            lines.next();
            let fence = lines.next().unwrap();
            let rest = lines.next().unwrap();
            let suffix = format!("\n{fence}\n");
            assert_eq!(rest.strip_suffix(&suffix).unwrap(), body);
        }
    }
    #[test]
    fn declaration_projection_keeps_one_signature_and_preserves_richer_types_and_extensions() {
        let fact: Fact = serde_json::from_value(serde_json::json!({
            "id":"method-id","kind":"declaration","owner":{"world":"w","producer":"p","kind":"source","id":"A.java"},
            "source":"A.java","name":"greet","signature":"p.A#greet(java.lang.String)","evidence_complete":true,"declaration_resolved":true,
            "attributes":{"language":"java","element_kind":"METHOD","modifiers":["public"],"declared_modifiers":["public"],"implicit_modifiers":[],"signature":"greet(java.lang.String name)","parameters":[{"name":"name","type":"java.lang.String"}],"returnType":"java.lang.String","visibility":"public","business_pk":42,"display_custom":"meaningful","vendor":{"hash":"proof","enabled":false,"unknown":null,"empty":"","display_name":"business label","business_pk":42}}
        })).unwrap();
        let body =
            String::from_utf8(record_text(&Request::default(), fact.clone().into()).unwrap())
                .unwrap();
        assert!(body.contains("method greet(java.lang.String) -> java.lang.String [public]"));
        assert!(body.contains("parameter-names: name"));
        assert!(!body.contains("signature:"));
        assert!(!body.contains("declared_modifiers"));
        assert!(body.contains("enabled: false"));
        assert!(body.contains("unknown: null"));
        assert!(body.contains("hash: proof"));
        assert!(body.contains("display_name: \"business label\""));
        assert!(body.contains("business_pk: 42"));
        assert!(body.contains("display_custom: meaningful"));
        assert!(body.contains("empty: \"\""));
        let projected = public_record(&Record::from(fact.clone()), false).unwrap();
        assert!(projected.get("name").is_none());
        assert!(projected["attributes"].get("signature").is_none());
        assert_eq!(projected["attributes"]["vendor"]["unknown"], Value::Null);
        let mut generic = fact;
        generic.signature = "p.A#greet(java.lang.Object)".into();
        let body =
            String::from_utf8(record_text(&Request::default(), generic.into()).unwrap()).unwrap();
        assert!(body.contains("type: java.lang.String"));
    }
    #[test]
    fn exceptional_dimensions_remain_independent() {
        let mut r = TaskResult::success(Output {
            evidence: Evidence::complete(),
            ..Default::default()
        });
        r.output.evidence.semantic = false;
        r.output.evidence.reasons.push("missing_inputs".into());
        r.output.cursor = "page:2".into();
        let text = String::from_utf8(encode(&Request::default(), &r).unwrap()).unwrap();
        assert!(text.contains("semantic: partial"));
        assert!(text.contains("delivery: page"));
        assert!(!text.contains("computation: limited"));
        assert!(text.ends_with("end\n"));
        let request = Request {
            operation: Operation::SearchCode,
            ..Default::default()
        };
        let mut limited = TaskResult::success(Output {
            evidence: Evidence::complete(),
            ..Default::default()
        });
        limited.output.evidence.enumeration = false;
        let text = String::from_utf8(encode(&request, &limited).unwrap()).unwrap();
        assert!(!text.contains("No matches"));
    }
}

#[cfg(test)]
mod export_tests {
    use super::*;
    #[test]
    fn two_exporters_never_clobber_each_other() {
        let root = tempfile::tempdir().unwrap();
        let path = root.path().join("answer.txt");
        let handles: Vec<_> = [b"first".to_vec(), b"second".to_vec()]
            .into_iter()
            .map(|body| {
                let path = path.clone();
                std::thread::spawn(move || export_file(&path, &body).is_ok())
            })
            .collect();
        assert_eq!(
            handles
                .into_iter()
                .map(|h| h.join().unwrap())
                .filter(|won| *won)
                .count(),
            1
        );
        let body = std::fs::read(path).unwrap();
        assert!(body == b"first" || body == b"second");
    }
}
