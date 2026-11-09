//! Progress reports and cancellation for long operations.

use crate::{Error, Result};

/// Told how far a long operation has got. Returning `false` cancels it, and the operation then fails with
/// [`Error::Cancelled`] after removing anything it half wrote.
pub trait Progress {
	/// `done` out of `total` bytes are finished.
	fn update(&mut self, done: u64, total: u64) -> bool;
}

impl<F: FnMut(u64, u64) -> bool> Progress for F {
	fn update(&mut self, done: u64, total: u64) -> bool {
		self(done, total)
	}
}

/// A [`Progress`] that never cancels and ignores every report.
pub struct Quiet;

impl Progress for Quiet {
	fn update(&mut self, _done: u64, _total: u64) -> bool {
		true
	}
}

/// Reports at most every `step` bytes, so a callback across JNI isn't called for every buffer.
pub(crate) struct Throttle<'a> {
	inner: &'a mut dyn Progress,
	last: u64,
	step: u64,
}

impl<'a> Throttle<'a> {
	pub(crate) fn new(inner: &'a mut dyn Progress) -> Self {
		Throttle { inner, last: 0, step: 4 << 20 }
	}

	pub(crate) fn report(&mut self, done: u64, total: u64) -> Result<()> {
		if done == total || done < self.last || done - self.last >= self.step {
			self.last = done;
			if !self.inner.update(done, total) {
				return Err(Error::Cancelled);
			}
		}
		Ok(())
	}
}
