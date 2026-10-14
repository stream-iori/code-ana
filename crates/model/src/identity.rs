//! Public identities have distinct Rust types while retaining their existing wire strings.
use serde::{Deserialize, Serialize};
use std::{borrow::Borrow, fmt, ops::Deref};
macro_rules! identity {
    ($($name:ident),* $(,)?) => {$ (
        #[derive(Debug,Clone,Default,PartialEq,Eq,PartialOrd,Ord,Hash,Serialize,Deserialize)]
        #[serde(transparent)]
        pub struct $name(String);
        impl $name { pub fn as_str(&self)->&str {&self.0} pub fn clear(&mut self) {self.0.clear();} }
        impl From<String> for $name {fn from(value:String)->Self {Self(value)}}
        impl From<&str> for $name {fn from(value:&str)->Self {Self(value.into())}}
        impl From<$name> for String {fn from(value:$name)->Self {value.0}}
        impl Borrow<str> for $name {fn borrow(&self)->&str {self.as_str()}}
        impl AsRef<str> for $name {fn as_ref(&self)->&str {self.as_str()}}
        impl Deref for $name {type Target=str;fn deref(&self)->&str {self.as_str()}}
        impl fmt::Display for $name {fn fmt(&self,f:&mut fmt::Formatter<'_>)->fmt::Result {f.write_str(self.as_str())}}
        impl PartialEq<&str> for $name {fn eq(&self,other:&&str)->bool {self.as_str()==*other}}
    )*};
}
identity!(
    TaskId,
    RequestId,
    ProjectId,
    CheckoutId,
    IndexTarget,
    RevisionId,
    FactId,
    WorldId
);
