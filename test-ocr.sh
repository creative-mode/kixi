#!/bin/bash

# =============================================================================
# Script de Teste OCR - Banco de Enunciados
# =============================================================================
# Uso: ./test-ocr.sh <caminho_para_imagem_ou_pdf>
#
# Exemplos:
#   ./test-ocr.sh prova.jpg
#   ./test-ocr.sh prova.pdf
#   ./test-ocr.sh ~/Downloads/exame_matematica.png
# =============================================================================

set -e

# Cores para output
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
BLUE='\033[0;34m'
NC='\033[0m' # No Color

# URLs dos serviços
PYTHON_OCR_URL="http://localhost:8000"
SPRING_API_URL="http://localhost:8080"

# Função para imprimir cabeçalho
print_header() {
    echo ""
    echo -e "${BLUE}============================================${NC}"
    echo -e "${BLUE}  $1${NC}"
    echo -e "${BLUE}============================================${NC}"
    echo ""
}

# Função para verificar se jq está instalado
check_jq() {
    if ! command -v jq &> /dev/null; then
        echo -e "${YELLOW}Aviso: jq não está instalado. Saída será em JSON bruto.${NC}"
        return 1
    fi
    return 0
}

# Função para verificar saúde dos serviços
check_health() {
    print_header "Verificando Saúde dos Serviços"

    echo -e "${YELLOW}1. Python OCR Service (porta 8000)...${NC}"
    if curl -s "$PYTHON_OCR_URL/ocr/health" | jq -e '.status == "healthy"' > /dev/null 2>&1; then
        echo -e "${GREEN}   ✓ OCR Service está saudável${NC}"
    else
        echo -e "${RED}   ✗ OCR Service não está respondendo${NC}"
        echo -e "${RED}   Execute: docker-compose up -d ocr-service${NC}"
        exit 1
    fi

    echo -e "${YELLOW}2. Spring Backend API (porta 8080)...${NC}"
    if curl -s "$SPRING_API_URL/api/v1/ocr/health" | jq -e '.status == "healthy"' > /dev/null 2>&1; then
        echo -e "${GREEN}   ✓ Backend API está saudável${NC}"
    else
        echo -e "${RED}   ✗ Backend API não está respondendo${NC}"
        echo -e "${RED}   Execute: docker-compose up -d backend-api${NC}"
        exit 1
    fi

    echo ""
}

# Função para testar extração via Python OCR
test_python_ocr() {
    local file_path="$1"
    print_header "Teste via Python OCR Service"

    echo -e "${YELLOW}Endpoint: POST $PYTHON_OCR_URL/ocr/v1/extract${NC}"
    echo -e "${YELLOW}Arquivo: $file_path${NC}"
    echo ""

    local response
    response=$(curl -s -X POST "$PYTHON_OCR_URL/ocr/v1/extract" \
        -F "images=@$file_path" \
        -H "Accept: application/json")

    if check_jq; then
        echo -e "${GREEN}=== Metadados Extraídos ===${NC}"
        echo "$response" | jq '.document // empty'

        echo ""
        echo -e "${GREEN}=== Questões Extraídas ===${NC}"
        echo "$response" | jq '.questions // empty'

        echo ""
        echo -e "${GREEN}=== Resumo ===${NC}"
        local num_questions
        num_questions=$(echo "$response" | jq '.questions | length // 0')
        local confidence
        confidence=$(echo "$response" | jq '.overallConfidence // 0')
        local status
        status=$(echo "$response" | jq -r '.status // "unknown"')

        echo -e "   Status: ${status}"
        echo -e "   Número de questões: ${num_questions}"
        echo -e "   Confiança geral: ${confidence}"

        echo ""
        echo -e "${GREEN}=== Avisos ===${NC}"
        echo "$response" | jq '.warnings // []'
    else
        echo "$response"
    fi
}

# Função para testar extração via Spring Backend
test_spring_backend() {
    local file_path="$1"
    print_header "Teste via Spring Backend API"

    echo -e "${YELLOW}Endpoint: POST $SPRING_API_URL/api/v1/ocr/extract/single${NC}"
    echo -e "${YELLOW}Arquivo: $file_path${NC}"
    echo ""

    local response
    response=$(curl -s -X POST "$SPRING_API_URL/api/v1/ocr/extract/single" \
        -F "file=@$file_path" \
        -H "Accept: application/json")

    if check_jq; then
        echo -e "${GREEN}=== Metadados Extraídos ===${NC}"
        echo "$response" | jq '.document // empty'

        echo ""
        echo -e "${GREEN}=== Questões (primeiras 3) ===${NC}"
        echo "$response" | jq '.questions[:3] // empty'

        echo ""
        echo -e "${GREEN}=== Resumo ===${NC}"
        local num_questions
        num_questions=$(echo "$response" | jq '.questions | length // 0')
        echo -e "   Número de questões: ${num_questions}"
    else
        echo "$response"
    fi
}

# Função para testar extração de exame estruturado
test_exam_extraction() {
    local file_path="$1"
    print_header "Teste de Extração de Exame (Formato Angolano)"

    echo -e "${YELLOW}Endpoint: POST $SPRING_API_URL/api/v1/ocr/extract/exam${NC}"
    echo -e "${YELLOW}Arquivo: $file_path${NC}"
    echo ""

    local response
    response=$(curl -s -X POST "$SPRING_API_URL/api/v1/ocr/extract/exam" \
        -F "files=@$file_path" \
        -H "Accept: application/json")

    if check_jq; then
        echo -e "${GREEN}=== Dados do Exame ===${NC}"
        echo "$response" | jq '{
            examType: .examType,
            subject: .subjectName,
            classGrade: .classGrade,
            course: .courseName,
            schoolYear: "\(.schoolYearStart)/\(.schoolYearEnd)",
            duration: .durationMinutes,
            variant: .variant,
            title: .title,
            totalMaxScore: .totalMaxScore
        } // empty'

        echo ""
        echo -e "${GREEN}=== Questões com Cotação ===${NC}"
        echo "$response" | jq '[.questions[]? | {
            numero: .number,
            subitems: .subitems,
            tipo: .type,
            cotacao: .cotacao,
            temImagem: .hasImage,
            texto: (.text.value | if . then (.[0:100] + (if (. | length) > 100 then "..." else "" end)) else null end)
        }]'

        echo ""
        echo -e "${GREEN}=== Resumo da Cotação ===${NC}"
        local total_cotacao
        total_cotacao=$(echo "$response" | jq '[.questions[]?.cotacao // 0] | add // 0')
        local num_questions
        num_questions=$(echo "$response" | jq '.questions | length // 0')

        echo -e "   Total de questões: ${num_questions}"
        echo -e "   Soma da cotação: ${total_cotacao} valores"

        echo ""
        echo -e "${GREEN}=== Imagens para Upload ===${NC}"
        echo "$response" | jq '.imagesToUpload // []'
    else
        echo "$response"
    fi
}

# Função para mostrar ajuda
show_help() {
    echo "Uso: $0 [opção] <arquivo>"
    echo ""
    echo "Opções:"
    echo "  -h, --help          Mostra esta ajuda"
    echo "  -c, --check         Verifica apenas a saúde dos serviços"
    echo "  -p, --python        Testa apenas via Python OCR Service"
    echo "  -s, --spring        Testa apenas via Spring Backend"
    echo "  -e, --exam          Testa extração de exame estruturado"
    echo "  -a, --all           Testa todos os endpoints (padrão)"
    echo ""
    echo "Exemplos:"
    echo "  $0 prova.jpg                    # Testa todos os endpoints"
    echo "  $0 -e prova.pdf                 # Testa extração de exame"
    echo "  $0 -p ~/Downloads/exame.png     # Testa apenas Python OCR"
    echo "  $0 -c                           # Verifica saúde dos serviços"
    echo ""
    echo "Formatos suportados: jpg, jpeg, png, pdf, webp, bmp, tiff"
}

# =============================================================================
# MAIN
# =============================================================================

# Parse argumentos
MODE="all"
FILE_PATH=""

while [[ $# -gt 0 ]]; do
    case $1 in
        -h|--help)
            show_help
            exit 0
            ;;
        -c|--check)
            MODE="check"
            shift
            ;;
        -p|--python)
            MODE="python"
            shift
            ;;
        -s|--spring)
            MODE="spring"
            shift
            ;;
        -e|--exam)
            MODE="exam"
            shift
            ;;
        -a|--all)
            MODE="all"
            shift
            ;;
        *)
            FILE_PATH="$1"
            shift
            ;;
    esac
done

# Verificar saúde dos serviços
check_health

# Se modo é apenas verificar, sair
if [[ "$MODE" == "check" ]]; then
    echo -e "${GREEN}Todos os serviços estão funcionando!${NC}"
    exit 0
fi

# Verificar se arquivo foi fornecido
if [[ -z "$FILE_PATH" ]]; then
    echo -e "${RED}Erro: Nenhum arquivo especificado.${NC}"
    echo ""
    show_help
    exit 1
fi

# Verificar se arquivo existe
if [[ ! -f "$FILE_PATH" ]]; then
    echo -e "${RED}Erro: Arquivo não encontrado: $FILE_PATH${NC}"
    exit 1
fi

# Executar testes baseado no modo
case $MODE in
    python)
        test_python_ocr "$FILE_PATH"
        ;;
    spring)
        test_spring_backend "$FILE_PATH"
        ;;
    exam)
        test_exam_extraction "$FILE_PATH"
        ;;
    all)
        test_python_ocr "$FILE_PATH"
        test_spring_backend "$FILE_PATH"
        test_exam_extraction "$FILE_PATH"
        ;;
esac

print_header "Teste Concluído"
echo -e "${GREEN}✓ Todos os testes foram executados com sucesso!${NC}"
echo ""
