// Helpers de mise en forme — reprennent le style du rapport de référence
// (polices Nexa Heavy / Nexa ExtraLight, bleu nuit 1F2A44, filet or C9A227, tableaux à en-tête sombre, lignes alternées F2F2F2).
const fs = require('fs');
const d = require('docx');
const {
  Paragraph, TextRun, Table, TableRow, TableCell, WidthType, ShadingType, BorderStyle,
  AlignmentType, ImageRun, VerticalAlign, HeadingLevel,
} = d;

const C = {
  navy: '1F2A44', gold: 'C9A227', grey: 'F2F2F2', border: 'BFBFBF', text: '222222',
  red: 'B3261E', orange: 'C76A00', green: '2E7D32', muted: '5F6B7A', blue: '2E74B5',
  goldBg: 'FBF6E6', redBg: 'FBEAEA', greenBg: 'EAF4EA',
};
const W = 9638; // largeur utile (A4, marges 1134)
// Polices demandées : Nexa Heavy pour les titres et le texte en gras, Nexa ExtraLight pour le texte courant.
const F = { heavy: 'Nexa Heavy', light: 'Nexa Extra Light' };
// Tout texte « gras » est rendu avec Nexa Heavy (graisse native, sans gras synthétique) ;
// le texte courant hérite de la police par défaut du document (Nexa ExtraLight).
function TR(o) {
  if (typeof o === 'string' || o.font) return new TextRun(o);
  if (o.bold) return TR({ ...o, font: F.heavy, bold: false });
  return new TextRun(o);
}

// Mini-markup : **gras**, `code`, __italique__
function runs(text, base = {}) {
  const out = [];
  const parts = String(text).split(/(\*\*[^*]+\*\*|`[^`]+`|__[^_]+__)/g).filter(s => s !== '');
  if (parts.length === 1 && base.bold && !/[`_]/.test(parts[0])) return [TR({ ...base, text: parts[0] })];
  for (const p of parts) {
    if (p.startsWith('**')) out.push(...runs(p.slice(2, -2).replace(/\u0000/g, ''), { ...base, bold: true }));
    else if (p.startsWith('`')) out.push(TR({ ...base, text: p.slice(1, -1), font: 'Consolas', size: Math.max((base.size || 21) - 3, 15) }));
    else if (p.startsWith('__')) out.push(TR({ ...base, text: p.slice(2, -2), italics: true }));
    else out.push(TR({ ...base, text: p }));
  }
  return out;
}

function P(text, opts = {}) {
  return new Paragraph({
    children: runs(text, { size: opts.size || 21, color: opts.color, bold: opts.bold, italics: opts.italics }),
    spacing: { after: opts.after ?? 120, before: opts.before ?? 0, line: opts.line ?? 276 },
    alignment: opts.align || AlignmentType.LEFT,
    keepNext: opts.keepNext,
    pageBreakBefore: opts.pageBreakBefore,
  });
}
const H1 = (t, o = {}) => new Paragraph({ heading: HeadingLevel.HEADING_1, children: [new TextRun(t)], pageBreakBefore: o.pageBreakBefore });
const H2 = t => new Paragraph({ heading: HeadingLevel.HEADING_2, children: [new TextRun(t)] });
const H3 = t => new Paragraph({ heading: HeadingLevel.HEADING_3, children: [new TextRun(t)] });

function bullets(items, opts = {}) {
  return items.map(t => new Paragraph({
    numbering: { reference: 'bul', level: 0 },
    children: runs(t, { size: opts.size || 20 }),
    spacing: { after: 60, line: 264 },
  }));
}

const thinBorder = { style: BorderStyle.SINGLE, size: 4, color: C.border };
const borders = { top: thinBorder, bottom: thinBorder, left: thinBorder, right: thinBorder };

// contenu de cellule : chaîne (avec \n pour paragraphes multiples) ou {t, color, bold, fill, align}
function cell(content, width, o = {}) {
  const spec = (typeof content === 'object' && content !== null && !Array.isArray(content)) ? content : { t: content };
  const lines = String(spec.t ?? '').split('\n');
  const size = o.size || 18;
  const children = lines.map(line => {
    const bullet = line.startsWith('• ');
    return new Paragraph({
      children: runs(bullet ? line.slice(2) : line, { size, color: spec.color || o.color, bold: spec.bold ?? o.bold }),
      spacing: { after: 30, line: 252 },
      alignment: spec.align || o.align || AlignmentType.LEFT,
      ...(bullet ? { numbering: { reference: 'bulcell', level: 0 } } : {}),
    });
  });
  return new TableCell({
    width: { size: width, type: WidthType.DXA },
    borders,
    shading: (spec.fill || o.fill) ? { fill: spec.fill || o.fill, type: ShadingType.CLEAR, color: 'auto' } : undefined,
    margins: { top: 60, bottom: 60, left: 100, right: 100 },
    verticalAlign: o.valign || VerticalAlign.TOP,
    children,
  });
}

function table(headers, rows, widths, opts = {}) {
  const sum = widths.reduce((a, b) => a + b, 0);
  if (sum !== W && !opts.width) throw new Error('Largeurs de colonnes = ' + sum + ' (attendu ' + W + ') pour ' + headers.join('|'));
  const head = new TableRow({
    tableHeader: true, cantSplit: true,
    children: headers.map((h, i) => cell(h, widths[i], { fill: C.navy, color: 'FFFFFF', bold: true, size: opts.size || 18 })),
  });
  const body = rows.map((r, ri) => new TableRow({
    cantSplit: opts.cantSplit !== false,
    children: r.map((c, i) => cell(c, widths[i], {
      fill: opts.zebra === false ? undefined : (ri % 2 === 1 ? C.grey : undefined),
      bold: opts.firstBold && i === 0 ? true : undefined,
      size: opts.size || 18,
    })),
  }));
  return new Table({ width: { size: opts.width || W, type: WidthType.DXA }, columnWidths: widths, rows: [head, ...body] });
}

// Encadré (recommandation / point clé) : bordure gauche épaisse colorée
function callout(title, lines, kind = 'gold') {
  const map = { gold: [C.gold, C.goldBg, C.navy], red: [C.red, C.redBg, C.red], green: [C.green, C.greenBg, C.green] };
  const [bc, bg, tc] = map[kind];
  const none = { style: BorderStyle.NONE, size: 0, color: 'FFFFFF' };
  const children = [new Paragraph({ children: [TR({ text: title, bold: true, color: tc, size: 21 })], spacing: { after: 80 } })];
  for (const l of lines) {
    children.push(new Paragraph({ numbering: { reference: 'bulcell', level: 0 }, children: runs(l, { size: 19 }), spacing: { after: 40, line: 252 } }));
  }
  return new Table({
    width: { size: W, type: WidthType.DXA }, columnWidths: [W],
    rows: [new TableRow({ cantSplit: true, children: [new TableCell({
      width: { size: W, type: WidthType.DXA },
      borders: { top: none, bottom: none, right: none, left: { style: BorderStyle.SINGLE, size: 36, color: bc } },
      shading: { fill: bg, type: ShadingType.CLEAR, color: 'auto' },
      margins: { top: 120, bottom: 100, left: 200, right: 160 },
      children,
    })] })],
  });
}

function code(lines) {
  const none = { style: BorderStyle.NONE, size: 0, color: 'FFFFFF' };
  return new Table({
    width: { size: W, type: WidthType.DXA }, columnWidths: [W],
    rows: [new TableRow({ cantSplit: true, children: [new TableCell({
      width: { size: W, type: WidthType.DXA },
      borders: { top: none, bottom: none, right: none, left: { style: BorderStyle.SINGLE, size: 18, color: C.navy } },
      shading: { fill: C.grey, type: ShadingType.CLEAR, color: 'auto' },
      margins: { top: 80, bottom: 80, left: 160, right: 120 },
      children: lines.map(l => new Paragraph({ children: [TR({ text: l, font: 'Consolas', size: 16 })], spacing: { after: 0, line: 240 } })),
    })] })],
  });
}

let figNo = 0;
function figure(path, caption, widthPx = 640) {
  const { width, height } = pngSize(path);
  const h = Math.round(widthPx * height / width);
  figNo += 1;
  return [
    new Paragraph({ alignment: AlignmentType.CENTER, keepNext: true, spacing: { before: 120, after: 60 },
      children: [new ImageRun({ type: 'png', data: fs.readFileSync(path), transformation: { width: widthPx, height: h },
        altText: { title: `Figure ${figNo}`, description: caption, name: `fig${figNo}` } })] }),
    new Paragraph({ alignment: AlignmentType.CENTER, spacing: { after: 200 },
      children: [TR({ text: `Figure ${figNo} — `, bold: true, size: 17, color: C.muted }), ...runs(caption, { size: 17, color: C.muted, italics: true })] }),
  ];
}
function pngSize(p) {
  const b = fs.readFileSync(p);
  return { width: b.readUInt32BE(16), height: b.readUInt32BE(20) };
}
const spacer = (after = 120) => new Paragraph({ children: [], spacing: { after } });

// Galerie d'écrans : grille sans bordure, `perRow` images par ligne, légende sous chaque image.
// items = [{ path, label, caption }]
function gallery(items, perRow, imgWidthPx) {
  const none = { style: BorderStyle.NONE, size: 0, color: 'FFFFFF' };
  const colW = Math.floor(W / perRow);
  const widths = Array.from({ length: perRow }, (_, i) => (i === perRow - 1 ? W - colW * (perRow - 1) : colW));
  const rows = [];
  for (let r = 0; r < items.length; r += perRow) {
    const cells = widths.map((w, c) => {
      const it = items[r + c];
      const children = [];
      if (it) {
        const { width, height } = pngSize(it.path);
        const h = Math.round(imgWidthPx * height / width);
        children.push(new Paragraph({ alignment: AlignmentType.CENTER, spacing: { before: 60, after: 40 },
          children: [new ImageRun({ type: 'png', data: fs.readFileSync(it.path), transformation: { width: imgWidthPx, height: h },
            altText: { title: it.label, description: it.caption, name: it.label } })] }));
        children.push(new Paragraph({ alignment: AlignmentType.CENTER, spacing: { after: 120, line: 240 },
          children: [TR({ text: `${it.label} — `, bold: true, size: 16, color: C.navy }), ...runs(it.caption, { size: 16, color: C.muted })] }));
      } else children.push(new Paragraph({ children: [] }));
      return new TableCell({ width: { size: w, type: WidthType.DXA }, borders: { top: none, bottom: none, left: none, right: none },
        margins: { top: 40, bottom: 40, left: 60, right: 60 }, verticalAlign: VerticalAlign.TOP, children });
    });
    rows.push(new TableRow({ cantSplit: true, children: cells }));
  }
  return new Table({ width: { size: W, type: WidthType.DXA }, columnWidths: widths, rows });
}

// Verdicts / gravités colorés
const sev = {
  crit: { t: 'Critique', color: '7A0000', bold: true },
  high: { t: 'Élevée', color: C.red, bold: true },
  med: { t: 'Moyenne', color: C.orange, bold: true },
  low: { t: 'Faible', color: C.green, bold: true },
  info: { t: 'Info', color: C.muted, bold: true },
};
const st = (t, color) => ({ t, color, bold: true });

module.exports = { d, C, W, F, TR, runs, P, H1, H2, H3, bullets, cell, table, callout, code, figure, spacer, sev, st, gallery };
