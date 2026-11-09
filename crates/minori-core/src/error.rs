use std::fmt;
use std::io;

/// Everything that can go wrong in `minori-core`.
#[derive(Debug)]
pub enum Error {
	/// Reading or writing a file failed.
	Io(io::Error),
	/// The data isn't what it claims to be, or is damaged.
	Format(String),
	/// The data is valid, but Minori can't handle it.
	Unsupported(String),
	/// A checksum didn't match.
	Checksum {
		/// The file whose checksum was wrong.
		name: String,
	},
	/// The caller cancelled the operation through its [`Progress`](crate::Progress).
	Cancelled,
}

/// A `Result` with [`Error`].
pub type Result<T> = std::result::Result<T, Error>;

impl Error {
	pub(crate) fn format(message: impl Into<String>) -> Self {
		Error::Format(message.into())
	}

	pub(crate) fn unsupported(message: impl Into<String>) -> Self {
		Error::Unsupported(message.into())
	}
}

impl fmt::Display for Error {
	fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
		match self {
			Error::Io(error) => write!(f, "{error}"),
			Error::Format(message) => write!(f, "{message}"),
			Error::Unsupported(message) => write!(f, "{message}"),
			Error::Checksum { name } => write!(f, "{name} doesn't match its checksum, so it may be damaged"),
			Error::Cancelled => write!(f, "Cancelled"),
		}
	}
}

impl std::error::Error for Error {
	fn source(&self) -> Option<&(dyn std::error::Error + 'static)> {
		match self {
			Error::Io(error) => Some(error),
			_ => None,
		}
	}
}

impl From<io::Error> for Error {
	fn from(error: io::Error) -> Self {
		Error::Io(error)
	}
}
