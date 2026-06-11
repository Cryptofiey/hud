const AdmZip = require('adm-zip');
const zip = new AdmZip('./logs.zip');
zip.extractAllTo('./extracted_logs', true);
