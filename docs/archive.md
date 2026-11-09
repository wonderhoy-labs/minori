# The .minorivm archive

A `.minorivm` file is one VM, for backups and for moving VMs between phones. It's a gzip-compressed tar, so any tar
can unpack it (`tar -xzf vm.minorivm`), holding, in this order:

1. `manifest.json`
2. `disks/<name>` for each disk, byte for byte
3. `SHA256SUMS`, in the format `sha256sum` writes, one line per disk (`<hex>  disks/<name>`)

`manifest.json`:

```json
{
	"format": "minori-vm",
	"version": 1,
	"vm": { "…": "the VM's settings, exactly as Minori stores them in config.json" },
	"disks": [
		{ "file": "root.img", "size": 8589934592 },
		{ "file": "seed.img", "size": 4194304 }
	]
}
```

- `format` is always `minori-vm`. A reader refuses a `version` newer than it knows.
- Disk names are plain file names: no `/`, no `\`, not starting with `.`, at most 100 bytes, so unpacking can't
  write outside its folder.
- Zero blocks compress to almost nothing, so a sparse 8 GiB disk holding 1.1 GB packs to about 400 MB.
- The extracted kernel and initrd aren't included. Importing extracts them again from the boot disk named in the
  settings.
- Passwords aren't included unless the user ticks "Include its password in exports". The SSH key Minori made for the
  VM never is.

Importing checks every disk against `SHA256SUMS` while writing it sparsely, and keeps nothing if any check fails.
The importer is `minori_core::archive::import` (or `import_from` for a stream), and the exporter `export` or
`export_to`. The `minori` command-line tool does both:

```bash
minori export vm.minorivm config.json root.img seed.img
minori import vm.minorivm unpacked/
```
