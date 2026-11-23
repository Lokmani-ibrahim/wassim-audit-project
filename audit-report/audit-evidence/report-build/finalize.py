"""Post-traitement du .docx enregistré par LibreOffice : mise à jour des champs à l'ouverture
dans Word (sommaire recalculé avec les vraies polices) et indices de substitution des polices Nexa."""
import re, sys, zipfile
src, dst = sys.argv[1], sys.argv[2]
zin = zipfile.ZipFile(src); out = zipfile.ZipFile(dst, 'w', zipfile.ZIP_DEFLATED)
for it in zin.infolist():
    if it.filename.endswith('/'): continue
    data = zin.read(it.filename)
    if it.filename == 'word/settings.xml':
        s = data.decode()
        if 'w:updateFields' not in s:
            anchors = ['<w:hdrShapeDefaults', '<w:footnotePr', '<w:endnotePr', '<w:compat', '<w:docVars', '<w:rsids', '<m:mathPr', '<w:themeFontLang', '<w:clrSchemeMapping', '<w:decimalSymbol', '<w:listSeparator', '</w:settings>']
            pos = min(i for i in (s.find(a) for a in anchors) if i >= 0)
            s = s[:pos] + '<w:updateFields w:val="true"/>' + s[pos:]
        data = s.encode()
    if it.filename == 'word/fontTable.xml':
        s = data.decode()
        s = re.sub(r'(<w:font w:name="Nexa (?:Extra Light|Heavy)">.*?)<w:family w:val="roman"/>', r'\1<w:family w:val="swiss"/>', s, flags=re.S)
        data = s.encode()
    out.writestr(it, data)
out.close()
import xml.etree.ElementTree as ET
z = zipfile.ZipFile(dst)
for n in z.namelist():
    if n.endswith(('.xml', '.rels')): ET.fromstring(z.read(n))
print('ok', dst, 'testzip:', z.testzip())
