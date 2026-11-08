//! A command-line tool to start from. Replace it with the project's own.

fn greeting() -> String {
	format!("{} {}", env!("CARGO_PKG_NAME"), env!("CARGO_PKG_VERSION"))
}

fn main() {
	println!("{}", greeting());
}

#[cfg(test)]
mod tests {
	use super::*;

	#[test]
	fn greeting_names_the_version() {
		assert!(greeting().ends_with(env!("CARGO_PKG_VERSION")));
	}
}
