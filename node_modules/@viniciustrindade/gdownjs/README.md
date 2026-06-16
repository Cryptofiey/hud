# @viniciustrindade/gdownjs

Node.js implementation of gdown - download files and folders from Google Drive.

> **Note:** This is a Node.js/TypeScript port of the original Python [gdown](https://github.com/wkentaro/gdown) library by [wkentaro](https://github.com/wkentaro). This package aims to provide the same functionality as the Python version but implemented in Node.js.

## Features

- Download files and folders from Google Drive
- Support for shared files with resource keys
- Format conversion for Google Docs/Sheets/Slides
- Cookie-based authentication
- File hash verification
- Rate limiting with exponential backoff
- Resume partial downloads
- Progress reporting
- Fuzzy URL extraction

## Installation

```bash
npm install @viniciustrindade/gdownjs
```

## Usage

### CLI

```bash
# Download a file
gdown https://drive.google.com/uc?id=FILE_ID

# Download to specific path
gdown https://drive.google.com/uc?id=FILE_ID -O output.pdf

# Download a folder
gdown https://drive.google.com/drive/folders/FOLDER_ID --folder -O ./downloads

# Use file ID directly
gdown --id FILE_ID -O output.pdf

# Convert Google Doc to PDF
gdown https://docs.google.com/document/d/DOC_ID --format pdf -O output.pdf

# Quiet mode
gdown https://drive.google.com/uc?id=FILE_ID --quiet

# Verify file hash
gdown https://drive.google.com/uc?id=FILE_ID --verify
```

### Programmatic API

```typescript
import { download, downloadFile, downloadFolder } from "@viniciustrindade/gdownjs";

// Download a file
const filePath = await download(
  "https://drive.google.com/uc?id=FILE_ID",
  "./output.pdf"
);

// Download a folder
const folderPath = await downloadFolder(
  "https://drive.google.com/drive/folders/FOLDER_ID",
  "./downloads",
  {
    quiet: false,
    verbose: true,
    remainingOk: true,
  }
);

// Download with options
const path = await downloadFile(
  "FILE_ID",
  "./output.pdf",
  {
    format: "pdf",
    verify: true,
    quiet: false,
  }
);
```

## Options

- `-O, --output <path>` - Output path (file or directory)
- `-f, --folder` - Download folder instead of file
- `--id <file_id>` - Use file ID instead of URL
- `--format <format>` - Format for Google Docs/Sheets/Slides (pdf, xlsx, pptx, etc.)
- `--proxy <proxy>` - Proxy URL
- `--speed <speed>` - Download speed limit (bytes per second)
- `--no-cookies` - Don't use cookies
- `--no-check-certificate` - Don't verify SSL certificates
- `--quiet` - Suppress output
- `--verbose` - Verbose output
- `--verify` - Verify file hash after download
- `--remaining-ok` - Continue if some files fail in folder download
- `--continue` - Resume partial downloads

## Supported Formats

### Google Docs
- pdf, docx, odt, rtf, txt, html, epub

### Google Sheets
- xlsx, ods, csv, tsv, pdf, html

### Google Slides
- pptx, odp, pdf, txt, png, jpg, svg

## URL Formats

The package supports various Google Drive URL formats:

- `https://drive.google.com/uc?id=FILE_ID`
- `https://drive.google.com/file/d/FILE_ID/view`
- `https://drive.google.com/drive/folders/FOLDER_ID`
- `https://docs.google.com/document/d/DOC_ID`
- `https://docs.google.com/spreadsheets/d/SHEET_ID`
- `https://docs.google.com/presentation/d/SLIDE_ID`

Fuzzy URL extraction is also supported - the package will attempt to extract file/folder IDs from any Google Drive URL.

## Cookies

Cookies are automatically loaded from `~/.gdown_cookies` or the `GDOWN_COOKIES` environment variable. Use cookies for authenticated downloads of private files.

## Caching

File metadata and hashes are cached in `~/.gdown_cache`. Use `--verify` to check file integrity after download.

## Rate Limiting

The package automatically handles Google Drive rate limits with exponential backoff. Downloads will retry up to 15 times with increasing delays.

## Error Handling

The package provides custom error classes:

- `GdownError` - Base error class
- `RateLimitError` - Rate limit exceeded
- `FileNotFoundError` - File not found
- `PermissionError` - Permission denied
- `VerificationError` - File verification failed

## Credits

This package is a Node.js/TypeScript port of the original Python [gdown](https://github.com/wkentaro/gdown) library created by [wkentaro](https://github.com/wkentaro).

Original Python gdown repository: https://github.com/wkentaro/gdown

## License

ISC

