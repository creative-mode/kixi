## Estudo de caso

O sistema proposto deverá permitir o cadastro e organização de enunciados de provas pertencentes a diferentes anos letivos, trimestres e disciplinas, sendo que cada enunciado poderá estar associado a uma turma, a um curso específico ou, alternativamente, ser válido para todos os cursos. Cada enunciado deverá conter informações como o tipo de prova (P1 ou P2), a duração, a variante, o ano letivo, o trimestre, a disciplina, a turma e, quando aplicável, o curso ao qual se destina.

Relativamente às questões, o sistema deverá armazenar todas as questões que compõem cada enunciado, registrando dados como o número da questão, o texto e a pontuação atribuída, permitindo assim a reconstrução fiel da prova original e a sua posterior utilização em processos de estudo e avaliação.

No que diz respeito aos usuários do sistema, estes deverão possuir uma conta de acesso, contendo informações essenciais para autenticação e segurança, como nome de utilizador (username), email, palavra-passe encriptada, estado da conta e datas de controlo de acesso. Cada conta estará associada a um perfil de usuário, no qual serão armazenados dados pessoais, como primeiro nome, último nome e fotografia. O sistema deverá ainda suportar diferentes papéis (roles), como administrador, professor e estudante, bem como o controlo de sessões de utilizador, garantindo segurança e rastreabilidade das ações realizadas.

Além da gestão dos enunciados, o sistema permitirá que os usuários realizem simulações de provas, nas quais poderão selecionar um enunciado específico e responder às respetivas questões. Cada simulação deverá ser registada no sistema, armazenando informações como o usuário que realizou a simulação, o enunciado utilizado, a data da realização, o tempo gasto e a nota final obtida.

Para cada simulação efetuada, o sistema deverá ainda guardar o histórico detalhado das respostas, possibilitando identificar as questões respondidas corretamente ou incorretamente, bem como a pontuação obtida em cada questão. Estas informações permitirão ao usuário analisar os seus erros, acompanhar a sua evolução ao longo do tempo e melhorar o seu desempenho académico.

É importante salientar que um usuário pode realizar várias simulações, contudo cada simulação está associada a um único usuário e a um único enunciado. Um enunciado pode ser utilizado em várias simulações, e cada simulação pode conter várias respostas correspondentes às questões do enunciado. Do mesmo modo, um curso pode estar associado a vários enunciados, mas um enunciado pode estar direcionado apenas a um curso específico ou ser comum a todos.

## MER

**anosLetivos** (id, anoInicio, anoFim);  </br>
**trimestres** (id, numero);  </br>
**disciplinas** (id, nome);  </br>
**cursos** (id, nome, descricao);  </br>
**turmas** (id, nome, classe);  </br>
**contas** (
id,
username,
email,
passwordHash,
emailVerificado,
ativo,
dataCriacao,
ultimoLogin
);  </br>
**usuarios** (
id,
primeiroNome,
ultimoNome,
foto,
idConta
);  </br>
**roles** (id, nome, descricao);
contaRoles (id, idConta, idRole);
sessoes (
id,
idConta,
token,
dataCriacao,
dataExpiracao,
ativo
);  </br>
**enunciados** (
id,
tipoProva,
duracaoMinutos,
variante,
idAnoLetivo,
idTrimestre,
idDisciplina,
idTurma,
idCurso,
criadoPor
);  </br>
**questoes** (
id,
numero,
texto,
pontuacao,
idEnunciado
);  </br>
**simulacoes** (
id,
idConta,
idEnunciado,
data,
notaFinal,
tempoGasto
);  </br>
**respostasSimulacao** (
id,
idSimulacao,
idQuestao,
respostaTexto,
correta,
pontuacaoObtida
); 
