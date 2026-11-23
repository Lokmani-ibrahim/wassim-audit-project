// Génère Rapport_Audit_Technique.docx
const fs = require('fs');
const path = require('path');
const L = require('./lib');
const { d, C, F } = L;
const {
  Document, Packer, Header, Footer, Paragraph, TextRun, ImageRun, AlignmentType, PageNumber, LevelFormat,
  HorizontalPositionRelativeFrom, VerticalPositionRelativeFrom, TextWrappingType, BorderStyle,
} = d;
const c1 = require('./content1');
const c2 = require('./content2');

const S = process.env.SCRATCH;
const OUT = process.argv[2];

// Papier à en-tête du modèle de référence (image pleine page derrière le texte, comme dans l'original)
const header = new Header({
  children: [new Paragraph({ children: [new ImageRun({
    type: 'jpg', data: fs.readFileSync(path.join(S, 'letterhead.jpg')),
    transformation: { width: 794, height: 1123 },
    floating: {
      horizontalPosition: { relative: HorizontalPositionRelativeFrom.PAGE, offset: 0 },
      verticalPosition: { relative: VerticalPositionRelativeFrom.PAGE, offset: 0 },
      behindDocument: true, allowOverlap: true, lockAnchor: true,
      wrap: { type: TextWrappingType.NONE },
    },
    altText: { title: 'Papier à en-tête', description: 'Papier à en-tête UFUK Connect', name: 'letterhead' },
  })] })],
});
const footer = new Footer({
  children: [new Paragraph({ alignment: AlignmentType.RIGHT, children: [
    new TextRun({ text: 'Page ', color: '808080', size: 16 }),
    new TextRun({ children: [PageNumber.CURRENT], color: '808080', size: 16 }),
  ] })],
});

const headingRun = { font: F.heavy, bold: false, color: C.navy };
const doc = new Document({
  creator: 'UFUK Connect',
  title: 'Rapport d’audit technique — Projet NEOS',
  description: 'Audit technique complet du projet NEOS',
  features: { updateFields: true },
  styles: {
    default: { document: { run: { font: F.light, size: 21 } } },
    paragraphStyles: [
      { id: 'Heading1', name: 'Heading 1', basedOn: 'Normal', next: 'Normal', quickFormat: true,
        run: { ...headingRun, size: 30 },
        paragraph: { spacing: { before: 360, after: 140 }, keepNext: true, keepLines: true, outlineLevel: 0,
          border: { bottom: { style: BorderStyle.SINGLE, size: 6, color: C.gold, space: 2 } } } },
      { id: 'Heading2', name: 'Heading 2', basedOn: 'Normal', next: 'Normal', quickFormat: true,
        run: { ...headingRun, size: 24 },
        paragraph: { spacing: { before: 220, after: 100 }, keepNext: true, keepLines: true, outlineLevel: 1 } },
      { id: 'Heading3', name: 'Heading 3', basedOn: 'Normal', next: 'Normal', quickFormat: true,
        run: { ...headingRun, size: 22 },
        paragraph: { spacing: { before: 160, after: 80 }, keepNext: true, outlineLevel: 2 } },
      { id: 'TOC1', name: 'toc 1', basedOn: 'Normal', next: 'Normal', run: { font: F.heavy, size: 20, color: C.navy },
        paragraph: { spacing: { before: 80, after: 20 } } },
      { id: 'TOC2', name: 'toc 2', basedOn: 'Normal', next: 'Normal', run: { font: F.light, size: 18 },
        paragraph: { spacing: { before: 0, after: 10 }, indent: { left: 300 } } },
    ],
  },
  numbering: { config: [
    { reference: 'bul', levels: [{ level: 0, format: LevelFormat.BULLET, text: '•', alignment: AlignmentType.LEFT,
      style: { paragraph: { indent: { left: 360, hanging: 240 } }, run: { color: C.gold } } }] },
    { reference: 'bulcell', levels: [{ level: 0, format: LevelFormat.BULLET, text: '•', alignment: AlignmentType.LEFT,
      style: { paragraph: { indent: { left: 240, hanging: 180 } } } }] },
  ] },
  sections: [{
    properties: { page: {
      size: { width: 11906, height: 16838 },
      margin: { top: 2300, right: 1134, bottom: 2500, left: 1134, header: 0, footer: 300 },
    } },
    headers: { default: header },
    footers: { default: footer },
    children: [
      ...c1.cover(), ...c1.toc(),
      ...c1.s1(), ...c1.s2(), ...c1.s3(), ...c1.s4(), ...c1.s5(), ...c1.s6(), ...c1.s7(),
      ...c2.s8(), ...c2.s9(), ...c2.s10(), ...c2.s11(), ...c2.s12(), ...c2.s13(), ...c2.s14(), ...c2.s15(), ...c2.s16(),
      ...c2.annexes(),
    ],
  }],
});

Packer.toBuffer(doc).then(buf => { fs.writeFileSync(OUT, buf); console.log('écrit', OUT, buf.length, 'octets'); });
