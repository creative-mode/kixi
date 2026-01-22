@startuml
' Kixi Backend - Diagrama de Classes (Domínio) - Entidades + Relacionamentos

skinparam monochrome true
skinparam shadowing false
skinparam classAttributeIconSize 0
skinparam stereotypeCBackgroundColor White
skinparam classFontSize 12
skinparam ArrowFontSize 11

' =====================
'      ENTIDADES
' =====================

class SchoolYear {
  + Long id
  + int startYear
  + int endYear
  --
  + Date createdAt
  + Date updatedAt
  + Date deletedAt
}

class Term {
  + Long id
  + int number
  + String name
  --
  + Date createdAt
  + Date updatedAt
  + Date deletedAt
}

class Subject {
  + String code PK
  + String name
  + String shortName
  --
  + Date createdAt
  + Date updatedAt
  + Date deletedAt
}

class Course {
  + String code PK
  + String name
  --
  + Date createdAt
  + Date updatedAt
  + Date deletedAt
}

class Class {
  + String code PK
  + String grade
  --
  + Long courseId FK
  + Long schoolYearId FK
  --
  + Date createdAt
  + Date updatedAt
  + Date deletedAt
}

class Account {
  + Long id
  + String username
  + String email
  + String passwordHash
  + Boolean emailVerified
  + Boolean active
  + Date lastLogin
  --
  + Date createdAt
  + Date updatedAt
  + Date deletedAt
}

class User {
  + Long id
  + String firstName
  + String lastName
  + String photo
  --
  + Long accountId FK (1:1)
  --
  + Date createdAt
  + Date updatedAt
  + Date deletedAt
}

class Role {
  + Long id
  + String name
  + String description
  --
  + Date createdAt
  + Date updatedAt
  + Date deletedAt
}

class Session {
  + Long id
  + String token
  + String ipAddress
  + Date expiresAt
  + Date lastUsed
  --
  + Long accountId FK
  --
  + Date createdAt
  + Date updatedAt
  + Date deletedAt
}

class Statement {
  + Long id
  + String examType
  + int durationMinutes
  + String variant
  + String title
  + String instructions
  + int totalMaxScore
  + Boolean visible
  --
  + Long schoolYearId FK
  + Long termId      FK
  + Long subjectId   FK
  + Long classId     FK
  + Long courseId    FK
  + Long createdBy   FK (Account)
  --
  + Date createdAt
  + Date updatedAt
  + Date deletedAt
}

class Question {
  + Long id
  + int number
  + String text
  + String questionType
  + int maxScore
  + int orderIndex
  --
  + Long statementId FK
  --
  + Date createdAt
  + Date updatedAt
  + Date deletedAt
}

class QuestionImage {
  + Long id
  + String imageUrl
  + String caption
  + int orderIndex
  --
  + Long questionId FK
  --
  + Date createdAt
  + Date updatedAt
  + Date deletedAt
}

class QuestionOption {
  + Long id
  + String optionLabel
  + String optionText
  + Boolean isCorrect
  + int orderIndex
  --
  + Long questionId FK
  --
  + Date createdAt
  + Date updatedAt
  + Date deletedAt
}

class Simulation {
  + Long id
  + Date startedAt
  + Date finishedAt
  + int timeSpentSeconds
  + float finalScore
  + String status
  --
  + Long accountId    FK
  + Long statementId  FK
  + Long schoolYearId FK
  --
  + Date createdAt
  + Date updatedAt
  + Date deletedAt
}

class SimulationAnswer {
  + Long id
  + float scoreObtained
  + Boolean isCorrect
  + String answerText
  + Date answeredAt
  --
  + Long simulationId     FK
  + Long questionId       FK
  + Long selectedOptionId FK (nullable)
  --
  + Date createdAt
  + Date updatedAt
  + Date deletedAt
}

' =====================
'      RELACIONAMENTOS
' =====================

' Temporais / Acadêmicos
Term "0..*" -- "1" SchoolYear   : pertence a
Class "0..*" -- "1" Course       : oferecido no
Class "0..*" -- "1" SchoolYear   : existe no

' Autenticação & Autorização
User "1" -- "1" Account          : possui (1:1)
Account "1" -- "0..*" Session    : tem sessões
Account "1" -- "0..*" Role       : possui (muitos-para-muitos implícito)

' Enunciado e estrutura da prova
Statement "1" -- "0..*" Question           : contém
Question "1" -- "0..*" QuestionImage       : tem imagens
Question "1" -- "0..*" QuestionOption      : tem opções

' Realização da prova (simulação)
Simulation "0..*" -- "1" Account           : feita por
Simulation "0..*" -- "1" Statement         : simula
Simulation "1" -- "0..*" SimulationAnswer  : possui respostas

SimulationAnswer "1" -- "1" Question       : responde
SimulationAnswer "0..1" -- "1" QuestionOption : seleciona (se aplicável)

' Referências cruzadas (FKs redundantes/removíveis em alguns casos)
Statement ..> Class      : classId
Statement ..> Course     : courseId
Statement ..> Subject    : subjectId
Statement ..> Term       : termId
Statement ..> SchoolYear : schoolYearId

Simulation ..> SchoolYear : schoolYearId (pode ser derivado do Statement)

@enduml