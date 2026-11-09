package ao.creativemode.kixi.training.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import ao.creativemode.kixi.shared.exception.ApiException;
import ao.creativemode.kixi.training.dto.MatrixItemRequest;
import java.util.List;
import org.junit.jupiter.api.Test;

/** O CSV da matriz (kixi#729): separadores, cabeçalho, níveis em PT/EN, linhas vazias e erros com o n.º da linha. */
class MatrixCsvParserTest {

    @Test
    void semicolonsWithHeaderAndPortugueseLevels() {
        List<MatrixItemRequest> items = MatrixCsvParser.parseOrThrow("""
                Tema;Questões;Cotação;Nível
                Funções;3;8;conhecimento
                Geometria;4;7,5;Compreensão
                Estatística;2;4.5;Aplicação
                """);
        assertThat(items).hasSize(3);
        assertThat(items.get(0)).satisfies(i -> {
            assertThat(i.topicName()).isEqualTo("Funções");
            assertThat(i.questionCount()).isEqualTo(3);
            assertThat(i.points()).isEqualTo(8.0);
            assertThat(i.cognitiveLevel()).isEqualTo("conhecimento");
        });
        assertThat(items.get(1).points()).isEqualTo(7.5);
        assertThat(items.get(2).points()).isEqualTo(4.5);
    }

    @Test
    void commasTabsAndEnglishLevelsWithoutHeader() {
        List<MatrixItemRequest> items = MatrixCsvParser.parseOrThrow("Funções,3,8,knowledge\nGeometria\t4\t7\tanalysis\n");
        assertThat(items).extracting(MatrixItemRequest::topicName).containsExactly("Funções", "Geometria");
        assertThat(items).extracting(MatrixItemRequest::cognitiveLevel).containsExactly("knowledge", "analysis");
        assertThat(items).extracting(MatrixItemRequest::points).containsExactly(8.0, 7.0);
    }

    @Test
    void englishHeaderEmptyLinesBomAndOptionalColumns() {
        List<MatrixItemRequest> items = MatrixCsvParser.parseOrThrow("﻿Topic;Questions\r\n\r\nÁlgebra;5\r\n\r\n  ;  \r\n".replace("  ;  \r\n", ""));
        assertThat(items).hasSize(1);
        assertThat(items.get(0).points()).isNull();
        assertThat(items.get(0).cognitiveLevel()).isNull();
    }

    @Test
    void quotedFieldsAreUnquoted() {
        List<MatrixItemRequest> items = MatrixCsvParser.parseOrThrow("\"Funções\";\"3\"");
        assertThat(items.get(0).topicName()).isEqualTo("Funções");
        assertThat(items.get(0).questionCount()).isEqualTo(3);
    }

    @Test
    void everyErrorCarriesItsLineNumber() {
        MatrixCsvParser.Parsed parsed = MatrixCsvParser.parse("""
                Tema;Questões;Cotação;Nível

                Funções;três;8;conhecimento
                Geometria;4;abc;aplicação
                Estatística;2;4;difícil
                ;3
                Probabilidades
                Funções;1
                """);
        // «Funções;1» na última linha é válida: a primeira «Funções» falhou, por isso ainda não foi registada
        assertThat(parsed.items()).hasSize(1);
        assertThat(parsed.errors()).containsExactly(
                "Linha 3: número de questões inválido «três»",
                "Linha 4: cotação inválida «abc»",
                "Linha 5: nível cognitivo desconhecido «difícil» (use conhecimento, compreensão, aplicação ou análise)",
                "Linha 6: tema em falta",
                "Linha 7: esperadas pelo menos 2 colunas (tema;questões)");
    }

    @Test
    void theRepeatedTopicIsReportedAgainstTheLineWhereItFirstAppeared() {
        MatrixCsvParser.Parsed parsed = MatrixCsvParser.parse("Funções;3\n\nfunções;1\n");
        assertThat(parsed.errors()).containsExactly("Linha 3: o tema «funções» já apareceu na linha 1");
    }

    @Test
    void parseOrThrowGivesA400WithTheLines() {
        assertThatThrownBy(() -> MatrixCsvParser.parseOrThrow("Funções;x\nGeometria;2;y"))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("CSV inválido")
                .hasMessageContaining("Linha 1: número de questões inválido «x»")
                .hasMessageContaining("Linha 2: cotação inválida «y»");
    }

    @Test
    void aDataLineWithABadNumberIsNotSwallowedAsAHeader() {
        MatrixCsvParser.Parsed parsed = MatrixCsvParser.parse("Funções;abc;8\nGeometria;2;3");
        assertThat(parsed.errors()).containsExactly("Linha 1: número de questões inválido «abc»");
    }

    @Test
    void onlyAnExactLabelWithNoNumericColumnIsAHeader() {
        // «Unidade 1: Funções» é uma linha de dados, não um cabeçalho por começar por «unidade»
        MatrixCsvParser.Parsed data = MatrixCsvParser.parse("Unidade 1: Funções;3;8\nGeometria;2;4");
        assertThat(data.errors()).isEmpty();
        assertThat(data.items()).extracting(MatrixItemRequest::topicName).containsExactly("Unidade 1: Funções", "Geometria");
        // um tema chamado «Tema» com número na 2.ª coluna também é dado
        assertThat(MatrixCsvParser.parse("Tema;3;8").items()).hasSize(1);
        // um rótulo com número mal escrito não se perde em silêncio
        assertThat(MatrixCsvParser.parse("Tema;abc;8").errors()).containsExactly("Linha 1: número de questões inválido «abc»");
        // cabeçalhos exatos, com ou sem acentos e maiúsculas
        assertThat(MatrixCsvParser.parse("Unidade Temática;Questões;Cotação\nFunções;3;8").items()).hasSize(1);
        assertThat(MatrixCsvParser.parse("CONTEÚDO;Itens\nFunções;3").items()).hasSize(1);
        // cotação numa coluna 3 numérica faz da 1.ª linha uma linha de dados
        assertThat(MatrixCsvParser.parse("Tema;x;5\nFunções;3").errors()).isNotEmpty();
    }

    @Test
    void textThatIsNotUtf8IsRefused() {
        MatrixCsvParser.Parsed parsed = MatrixCsvParser.parse("Fun\uFFFD\uFFFDes;3;8");
        assertThat(parsed.items()).isEmpty();
        assertThat(parsed.errors()).containsExactly("o ficheiro deve estar em UTF-8");
    }

    @Test
    void aByteOrderMarkIsIgnored() {
        assertThat(MatrixCsvParser.parse("\uFEFFTema;Questões\nFunções;3").items()).hasSize(1);
        assertThat(MatrixCsvParser.parse("\uFEFFFunções;3").items().get(0).topicName()).isEqualTo("Funções");
    }

    @Test
    void numbersBeyondTheLimitsAreErrors() {
        assertThat(MatrixCsvParser.parse("Funções;101").errors()).containsExactly("Linha 1: número de questões inválido «101»");
        assertThat(MatrixCsvParser.parse("Funções;3;10000").errors()).containsExactly("Linha 1: cotação inválida «10000»");
        assertThat(MatrixCsvParser.parse("Funções;100;9999,99").errors()).isEmpty();
    }

    @Test
    void anEmptyFileIsAnError() {
        assertThat(MatrixCsvParser.parse("  \n ").errors()).isNotEmpty();
        assertThat(MatrixCsvParser.parse("Tema;Questões\n").errors()).containsExactly("o ficheiro não tem linhas de temas");
    }
}
