//! Public declaration vocabulary for both source tags of the TypeScript backend.
use serde::{Deserialize, Serialize};

#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "snake_case")]
pub enum DeclarationKind {
    Module,
    Class,
    Interface,
    Function,
    Method,
    Constructor,
    GetAccessor,
    SetAccessor,
    Property,
    Variable,
    Parameter,
    TypeAlias,
    TypeParameter,
    Enum,
    EnumMember,
    Namespace,
    Import,
    Export,
    CallSignature,
    ConstructSignature,
    IndexSignature,
}
impl DeclarationKind {
    pub const ALL: [Self; 21] = [
        Self::Module,
        Self::Class,
        Self::Interface,
        Self::Function,
        Self::Method,
        Self::Constructor,
        Self::GetAccessor,
        Self::SetAccessor,
        Self::Property,
        Self::Variable,
        Self::Parameter,
        Self::TypeAlias,
        Self::TypeParameter,
        Self::Enum,
        Self::EnumMember,
        Self::Namespace,
        Self::Import,
        Self::Export,
        Self::CallSignature,
        Self::ConstructSignature,
        Self::IndexSignature,
    ];
    pub const fn as_str(self) -> &'static str {
        match self {
            Self::Module => "module",
            Self::Class => "class",
            Self::Interface => "interface",
            Self::Function => "function",
            Self::Method => "method",
            Self::Constructor => "constructor",
            Self::GetAccessor => "get_accessor",
            Self::SetAccessor => "set_accessor",
            Self::Property => "property",
            Self::Variable => "variable",
            Self::Parameter => "parameter",
            Self::TypeAlias => "type_alias",
            Self::TypeParameter => "type_parameter",
            Self::Enum => "enum",
            Self::EnumMember => "enum_member",
            Self::Namespace => "namespace",
            Self::Import => "import",
            Self::Export => "export",
            Self::CallSignature => "call_signature",
            Self::ConstructSignature => "construct_signature",
            Self::IndexSignature => "index_signature",
        }
    }
    pub const fn type_kind(self) -> Option<&'static str> {
        match self {
            Self::Class => Some("class"),
            Self::Interface => Some("interface"),
            Self::Enum => Some("enum"),
            _ => None,
        }
    }
}

#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "snake_case")]
pub enum Visibility {
    Public,
    Protected,
    Private,
    Module,
    Namespace,
    Local,
}
impl Visibility {
    pub const ALL: [Self; 6] = [
        Self::Public,
        Self::Protected,
        Self::Private,
        Self::Module,
        Self::Namespace,
        Self::Local,
    ];
    pub const fn as_str(self) -> &'static str {
        match self {
            Self::Public => "public",
            Self::Protected => "protected",
            Self::Private => "private",
            Self::Module => "module",
            Self::Namespace => "namespace",
            Self::Local => "local",
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    #[test]
    fn vocabulary_serializes_without_java_kind_coercion() {
        for kind in DeclarationKind::ALL {
            let encoded = serde_json::to_value(kind).unwrap();
            assert_eq!(encoded, kind.as_str());
            assert_eq!(
                serde_json::from_value::<DeclarationKind>(encoded).unwrap(),
                kind
            );
        }
        for visibility in Visibility::ALL {
            assert_eq!(
                serde_json::to_value(visibility).unwrap(),
                visibility.as_str()
            );
        }
        assert_eq!(DeclarationKind::Class.type_kind(), Some("class"));
        assert_eq!(DeclarationKind::TypeAlias.type_kind(), None);
    }
}
