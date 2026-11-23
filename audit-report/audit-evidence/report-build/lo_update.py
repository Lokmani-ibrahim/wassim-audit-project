"""Ouvre un .docx dans LibreOffice (headless), met à jour le sommaire et les champs,
puis enregistre une copie .docx (sommaire rempli) et un export PDF."""
import os, subprocess, sys, time
import uno
from com.sun.star.beans import PropertyValue

src, out_docx, out_pdf = sys.argv[1], sys.argv[2], sys.argv[3]
profile = os.path.join(os.path.dirname(os.path.abspath(out_pdf)), 'lo-profile')
port = 2099
proc = subprocess.Popen(['soffice', '--headless', '--norestore', '--nologo', '--nodefault',
                         f'-env:UserInstallation=file://{profile}',
                         f'--accept=socket,host=127.0.0.1,port={port};urp;'],
                        stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)

def prop(n, v):
    p = PropertyValue(); p.Name = n; p.Value = v; return p

ctx = None
local = uno.getComponentContext()
resolver = local.ServiceManager.createInstanceWithContext('com.sun.star.bridge.UnoUrlResolver', local)
for _ in range(60):
    try:
        ctx = resolver.resolve(f'uno:socket,host=127.0.0.1,port={port};urp;StarOffice.ComponentContext')
        break
    except Exception:
        time.sleep(1)
if ctx is None:
    proc.kill(); sys.exit('LibreOffice injoignable')
desktop = ctx.ServiceManager.createInstanceWithContext('com.sun.star.frame.Desktop', ctx)
doc = desktop.loadComponentFromURL(uno.systemPathToFileUrl(os.path.abspath(src)), '_blank', 0, (prop('Hidden', True),))
try:
    for _ in range(2):  # 2 passes : les numéros de page peuvent bouger après le remplissage du sommaire
        idx = doc.getDocumentIndexes()
        for i in range(idx.getCount()):
            idx.getByIndex(i).update()
        doc.getTextFields().refresh()
        doc.refresh()
    doc.storeToURL(uno.systemPathToFileUrl(os.path.abspath(out_docx)), (prop('FilterName', 'MS Word 2007 XML'),))
    doc.storeToURL(uno.systemPathToFileUrl(os.path.abspath(out_pdf)), (prop('FilterName', 'writer_pdf_Export'),))
    print('indexes:', doc.getDocumentIndexes().getCount())
finally:
    doc.close(True)
    try:
        desktop.terminate()
    except Exception:
        pass
    proc.wait(timeout=30)
