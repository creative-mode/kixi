// Testes do verificador de higiene de texto:  node --test .github/scripts/text-hygiene.test.js
//
// Cada teste parte de um caso real que passou despercebido, para que o checker não
// possa regredir sem que um destes testes falhe.
const test = require('node:test');
const assert = require('node:assert/strict');
const { topLevelProblems, stripForBraces, ENCODING_SUSPECTS } = require('./text-hygiene.js');

const problems = (lines) => topLevelProblems(lines, 'X.java').map((p) => p.line);

// ── Indentação ────────────────────────────────────────────────────────────

test('um @Test encostado à coluna zero é assinalado', () => {
  assert.deepEqual(problems([
    'class T {',
    '@Test',
    'void x() {',
    '    assert ok;',
    '}',
    '}',
  ]), [2, 3]);
});

test('um javadoc de membro encostado à coluna zero é assinalado', () => {
  // O caso do GlobalExceptionHandler: /** na coluna zero com o resto do javadoc
  // alinhado. As linhas seguintes do javadoc levam um espaço, como sempre.
  assert.deepEqual(problems([
    'class T {',
    '/**',
    ' * Texto.',
    ' */',
    '    public void x() {',
    '    }',
    '}',
  ]), [2]);
});

test('um método normal não é assinalado', () => {
  assert.deepEqual(problems([
    'class T {',
    '    @Test',
    '    void x() {',
    '        assert ok;',
    '    }',
    '}',
  ]), []);
});

// ── Falsos positivos que não podem aparecer ───────────────────────────────

test('o fecho final da classe fica na coluna zero', () => {
  assert.deepEqual(problems(['class T {', '    void x() {', '    }', '}']), []);
});

test('a declaração da classe fica na coluna zero', () => {
  assert.deepEqual(problems(['public final class T {', '    void x() {', '    }', '}']), []);
});

test('o cabeçalho de um record fica na coluna zero', () => {
  assert.deepEqual(problems(['public record T(', '        Long a,', '        String b', ') {}']), []);
});

test('o abre-chavetas de uma interface multilinha fica na coluna zero', () => {
  assert.deepEqual(problems([
    'public interface T',
    '    extends R<Long>',
    '{',
    '    Flux<Long> all();',
    '}',
  ]), []);
});

// ── Javadoc com {@code} não conta como chave ──────────────────────────────

test('um javadoc com {@code {}} não estraga a contagem de profundidade', () => {
  // O {@code {}} abre e fecha chavetas que nao sao do codigo. Sem de as remover,
  // a profundidade deixa de zeroar e o resto do ficheiro deixa de ser verificado.
  assert.deepEqual(problems([
    'class T {',
    '    /**',
    '     * Faz {@code algo} de {@link #outro()}.',
    '     */',
    '@Test',
    '    void x() {',
    '    }',
    '}',
  ]), [5]);
});

test('um comentario de linha não conta como chave', () => {
  assert.deepEqual(problems([
    'class T {',
    '    void x() { // abre {',
    '    }',
    '@Test',
    '    void y() {',
    '    }',
    '}',
  ]), [4]);
});

test('uma string com chavetas não conta', () => {
  assert.deepEqual(problems([
    'class T {',
    '    String s = "{";',
    '    void x() {',
    '    }',
    '@Test',
    '    void y() {',
    '    }',
    '}',
  ]), [5]);
});

test('o https:// de um comentario não abre um comentario', () => {
  const { code, block } = stripForBraces('    // ver https://exemplo.com/x {', false);
  assert.equal(code.includes('//'), false);
  assert.equal(block, false);
});

// ── Encoding ──────────────────────────────────────────────────────────────

test('os padrões de mojibake estão construídos, não escritos à mão', () => {
  // Se alguém reescrever os padrões como literais, este ficheiro passa a ter
  // mojibake e o checker sinaliza-se a si próprio.
  const source = require('node:fs').readFileSync(require.resolve('./text-hygiene.js'), 'utf8');
  assert.equal(source.includes(String.fromCodePoint(0x00e2, 0x20ac)), false);
  assert.equal(ENCODING_SUSPECTS.length, 3);
});

test('o padrao de mojibake casa com bytes UTF-8 reinterpretados', () => {
  const original = '    // ─── sec ───\n';
  const mangled = Buffer.from(original, 'utf8').toString('latin1');
  assert.equal(ENCODING_SUSPECTS.some((r) => r.test(mangled)), true);
});

test('o padrao de mojibake nao acusa texto UTF-8 legitimo', () => {
  // Uma caixa de verdade, com acentos e travessoes: nada disto e mojibake.
  const legitimate = '    // ─── Secção ─── Ação · Înscrição\n';
  assert.equal(ENCODING_SUSPECTS.some((r) => r.test(legitimate)), false);
});