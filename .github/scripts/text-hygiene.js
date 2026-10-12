// Verifica ficheiros de texto do repositório: encoding e indentação.
//
//   node .github/scripts/text-hygiene.js            # verifica tudo
//   node .github/scripts/text-hygiene.js <ficheiro> # verifica um ficheiro
//
// Existe porque os dois defeitos abaixo passaram despercebidos várias vezes e
// nenhum deles falha o build:
//
// 1. Encoding. Ao resolver conflitos entre ficheiros em encodings diferentes, os
//    caracteres de caixa (─ U+2500) ficam reinterpretados como Latin-1 e aparece
//    `â”€`. Já aconteceu em InstitutionAccessServiceTest e em
//    StatementApprovalAuthorizationTest. Nenhuma regra do git previne isto:
//    `working-tree-encoding` assume que a working tree já está em UTF-8 e grava
//    os bytes tal e qual.
//
// 2. Indentação. Um `@Test`, um `@ExceptionHandler` ou um javadoc encostados à
//    coluna zero enquanto o resto da classe usa quatro espaços. Passou por revisão
//    humana duas vezes.
//
// Saída: uma linha por problema, e sai com 1 se algum existir.

const fs = require('node:fs');
const path = require('node:path');

const ROOT = path.resolve(__dirname, '..', '..');

// Só ficheiros de texto. Binários (imagens, ficheiros de audio) não interessam.
const TEXT_DIRS = ['services', '.github', 'conceptual'];

// 'â' e 'Ã' seguidos de um caracter de Windows-1252: o sinal clássico de UTF-8
// reinterpretado como Latin-1. Construídos por ponto de código para que este
// ficheiro não se auto-sinale.
const A_CIRCUMFLEX = String.fromCodePoint(0x00e2); // â
const A_TILDE = String.fromCodePoint(0x00c3); // Ã
const BOM_AS_TEXT = String.fromCodePoint(0x00ef, 0x00bb, 0x00bf); // BOM em Latin-1

const ENCODING_SUSPECTS = [
  // 'â' ou 'Ã' seguidos de um caracter de Windows-1252.
  new RegExp(`[${A_CIRCUMFLEX}${A_TILDE}][\\u0080-\\u00bf]`),
  // BOM escrito como texto em vez de bytes.
  new RegExp(BOM_AS_TEXT),
  // Smart quotes de Windows-1252 vistas como Latin-1.
  new RegExp(`${A_CIRCUMFLEX}\\u20ac`),
];

// Extensões onde o encoding tem de ser UTF-8 válido.
const UTF8_EXT = new Set(['.java', '.sql', '.yml', '.yaml', '.json', '.md', '.js', '.mjs', '.properties']);

/**
 * Remove comentários e strings para contar chavetas sem confundir o `{@code ...}`
 * dos javadocs.
 */
function stripForBraces(line, inBlock) {
  let s = line;
  let block = inBlock;
  if (block) {
    if (s.includes('*/')) {
      s = s.split('*/', 2)[1];
      block = false;
    } else {
      return { code: '', block };
    }
  }
  s = s.replace(/\/\*.*?\*\//g, '');
  if (s.includes('/*')) {
    s = s.split('/*', 1)[0];
    block = true;
  }
  // Não partir o "//" de um "https://".
  s = s.replace(/(?<!:)\/\/.*$/, '');
  s = s.replace(/"(\\.|[^"\\])*"/g, '""');
  return { code: s, block };
}

/** Os pontos em que a profundidade de classes fecha e a linha pode ser de topo. */
function topLevelProblems(rawLines, filename) {
  const problems = [];
  // O estado de bloco de comentário propaga de uma linha para a seguinte, por
  // isso o stripping tem de ser sequencial.
  let inBlock = false;
  const codes = [];
  for (const line of rawLines) {
    const { code, block } = stripForBraces(line, inBlock);
    inBlock = block;
    codes.push(code);
  }

  const classAt = codes.findIndex((c) =>
    /^\s*(?:public\s+|final\s+|abstract\s+)*(?:class|interface|enum|record)\s/.test(c));
  if (classAt === -1) return problems;

  let depth = 0;
  for (let i = classAt + 1; i < codes.length; i += 1) {
    const code = codes[i];
    const raw = rawLines[i];
    // A linha original pode ser um javadoc, que o stripper removeu do código.
    if (!code.trim() && !raw.trim()) continue;
    if (depth === 0 && raw.trim() && !/^\s/.test(raw)) {
      const strippedRaw = raw.trim();
      // A declaração da classe, o fecho final, e a linha de cabeçalho de um record
      // ou interface — que Java põe na coluna zero por convenção.
      const isDeclaration = /^(?:public|private|protected)?\s*(?:static\s+)?(?:final\s+)?(?:abstract\s+)?(?:class|interface|enum|record)\b/.test(strippedRaw);
      const isRecordHeader = /^\)\s*\{\s*\}?$/.test(strippedRaw);
      // Uma interface escrita em várias linhas abre o corpo assim, e é a
      // convenção do Java.
      const isInterfaceBrace = strippedRaw === '{';
      // As anotacoes da classe ficam na coluna zero por convencao (@Service,
      // @RestController, Lombok). As de membro nao: um @Test encostado a coluna
      // zero e o defeito que se quer apanhar, e @Test/@BeforeEach e
      // precisamente o que aparece deslocado num teste.
      const CLASS_ANNOTATIONS = /^(?:@)(?:Override|Deprecated|SuppressWarnings|SafeVarargs|FunctionalInterface|ParameterizedTest|RepeatedTest|SpringBootTest|WebFluxTest|DataJpaTest|Import|TestPropertySource|TestInstance|ExtendWith|MockBean|SpyBean|WebMvcTest|JsonTest)\b/;
      const isAnnotation = CLASS_ANNOTATIONS.test(strippedRaw);
      if (!isDeclaration && !isAnnotation && !isRecordHeader && !isInterfaceBrace
          && strippedRaw !== '}') {
        problems.push({
          filename,
          line: i + 1,
          text: strippedRaw.slice(0, 60),
        });
      }
    }
    depth += (code.match(/{/g) || []).length - (code.match(/}/g) || []).length;
  }
  return problems;
}

/** Todos os ficheiros versionados sob os directórios de texto. */
function walk(dir, acc = []) {
  for (const entry of fs.readdirSync(dir, { withFileTypes: true })) {
    if (entry.name === 'node_modules' || entry.name === 'target' || entry.name === '.git') continue;
    const full = path.join(dir, entry.name);
    if (entry.isDirectory()) {
      walk(full, acc);
    } else if (entry.isFile()) {
      acc.push(full);
    }
  }
  return acc;
}

function checkFile(full) {
  const rel = path.relative(ROOT, full);
  const problems = [];
  let raw;

  try {
    raw = fs.readFileSync(full);
  } catch {
    return problems;
  }

  const ext = path.extname(full).toLowerCase();

  // 1. Encoding: tem de decodificar como UTF-8, e sem marcas de mojibake.
  // U+FFFD, o caracter que o descodificador põe quando os bytes não são UTF-8.
// Construído por ponto de código para que este ficheiro não se auto-sinale.
const REPLACEMENT_CHAR = String.fromCodePoint(0xfffd);

if (UTF8_EXT.has(ext)) {
    const text = raw.toString('utf8');
    if (text.includes(REPLACEMENT_CHAR)) {
      problems.push({ filename: rel, line: 0, text: 'contém U+FFFD: bytes que não são UTF-8 válido' });
    }
    for (const suspect of ENCODING_SUSPECTS) {
      if (suspect.test(text)) {
        problems.push({
          filename: rel,
          line: 0,
          text: `aparência de mojibake (${suspect}): UTF-8 reinterpretado como Latin-1`,
        });
        break;
      }
    }
  }

  // 2. Indentação, só em Java.
  if (ext === '.java') {
    problems.push(...topLevelProblems(raw.toString('utf8').split('\n'), rel));
  }

  return problems;
}

function main(argv) {
  const explicit = argv.slice(2);
  const files = explicit.length
    ? explicit.map((f) => path.resolve(process.cwd(), f))
    : TEXT_DIRS
        .map((d) => path.join(ROOT, d))
        .filter((d) => fs.existsSync(d))
        .flatMap((d) => walk(d));

  const problems = files.flatMap(checkFile);

  for (const p of problems) {
    const where = p.line ? `${p.filename}:${p.line}` : p.filename;
    process.stdout.write(`${where}  ${p.text}\n`);
  }

  if (problems.length) {
    process.stdout.write(`\n${problems.length} problema(s) de higiene de texto.\n`);
    process.exitCode = 1;
  } else {
    process.stdout.write(`OK: ${files.length} ficheiro(s) verificado(s), nenhum problema.\n`);
  }
}

if (require.main === module) main(process.argv);

module.exports = { checkFile, topLevelProblems, stripForBraces, ENCODING_SUSPECTS, UTF8_EXT };