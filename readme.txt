================================================================================
  LLM RAG PDF Summary — Quarkus + LangChain4j + Ollama
================================================================================

Application Quarkus qui analyse des fichiers PDF via un pipeline RAG (Retrieval
Augmented Generation) avec un LLM local via Ollama. Aucune clé API requise.

--------------------------------------------------------------------------------
  ARCHITECTURE
--------------------------------------------------------------------------------

  PDF upload
     │
     ▼
  PDFBox (extraction texte)
     │
     ▼
  DocumentSplitter (chunks de 1000 chars, 200 overlap)
     │
     ▼
  all-MiniLM-L6-v2 (embedding local, embarqué dans le JAR, 384 dimensions)
     │
     ▼
  InMemoryEmbeddingStore (vector store)
     │
     ▼  ◄── Recherche sémantique (similarité cosinus)
  Ollama / mistral (LLM local sur :11434)
     │
     ▼
  Résumé / Réponse JSON

Composants :
  - LLM chat     : Ollama (mistral, llama3, gemma2, phi3...) — 100% local
  - Embeddings   : all-MiniLM-L6-v2 — embarqué dans le JAR, sans réseau
  - Vector store : InMemoryEmbeddingStore — en RAM, réinitialisé au redémarrage

--------------------------------------------------------------------------------
  PRÉREQUIS
--------------------------------------------------------------------------------

  - Java 21+
  - Maven 3.9+
  - Ollama installé et un modèle téléchargé

--------------------------------------------------------------------------------
  INSTALLATION D'OLLAMA
--------------------------------------------------------------------------------

1. Installer Ollama
     Linux / Mac :
       curl -fsSL https://ollama.com/install.sh | sh

     Windows :
       Télécharger l'installeur sur https://ollama.com

2. Télécharger un modèle (une seule fois)
     ollama pull mistral        # recommandé — ~4 Go RAM
     ollama pull llama3         # plus performant — ~5 Go RAM
     ollama pull phi3           # léger et rapide — ~2 Go RAM
     ollama pull gemma2         # excellent en français — ~5 Go RAM

3. Ollama démarre automatiquement en arrière-plan sur http://localhost:11434

--------------------------------------------------------------------------------
  LANCER L'APPLICATION
--------------------------------------------------------------------------------

  mvn quarkus:dev

  L'application démarre sur http://localhost:8080

  Note : au premier appel, Ollama charge le modèle en mémoire (~10-30 sec).
  Les appels suivants sont beaucoup plus rapides.

--------------------------------------------------------------------------------
  API — ENDPOINTS
--------------------------------------------------------------------------------

  POST /api/pdf/summarize   Upload un PDF et génère un résumé structuré
  POST /api/pdf/ask         Pose une question sur le document indexé
  GET  /api/pdf/health      Vérifie l'état du service

---

  POST /api/pdf/summarize
  -----------------------
  Paramètres (multipart/form-data) :
    file          PDF à analyser                        (obligatoire)
    language      Langue de la réponse                  (optionnel, défaut: français)
    customPrompt  Instruction personnalisée              (optionnel)

  Exemple :
    curl -X POST http://localhost:8080/api/pdf/summarize \
      -F "file=@/chemin/vers/document.pdf" \
      -F "language=français"

  Exemple avec prompt personnalisé :
    curl -X POST http://localhost:8080/api/pdf/summarize \
      -F "file=@rapport.pdf" \
      -F "language=français" \
      -F "customPrompt=Concentre-toi sur les aspects financiers"

  Réponse JSON :
    {
      "fileName": "rapport.pdf",
      "status": "SUCCESS",
      "summary": "...",
      "keyPoints": ["• Point 1", "• Point 2", ...],
      "chunkCount": 12,
      "processingTimeMs": 4200
    }

---

  POST /api/pdf/ask
  -----------------
  Paramètres (application/json) :
    customPrompt  La question à poser sur le document   (obligatoire)
    language      Langue de la réponse                  (optionnel, défaut: français)

  Prérequis : avoir d'abord uploadé un PDF via /api/pdf/summarize
              (le vector store est en mémoire, il se vide au redémarrage)

  Exemple :
    curl -X POST http://localhost:8080/api/pdf/ask \
      -H "Content-Type: application/json" \
      -d '{"customPrompt": "Quels sont les risques mentionnés ?", "language": "français"}'

  Réponse JSON :
    {
      "question": "Quels sont les risques mentionnés ?",
      "answer": "..."
    }

---

  GET /api/pdf/health
  -------------------
  Exemple :
    curl http://localhost:8080/api/pdf/health

  Réponse :
    {"status": "UP", "service": "LLM RAG PDF Summary"}

--------------------------------------------------------------------------------
  CONFIGURATION (src/main/resources/application.properties)
--------------------------------------------------------------------------------

  Changer de modèle Ollama :
    quarkus.langchain4j.ollama.chat-model.model-id=llama3

  Changer l'URL Ollama (si sur une autre machine) :
    quarkus.langchain4j.ollama.base-url=http://192.168.1.10:11434

  Ajuster le timeout (utile pour les gros documents) :
    quarkus.langchain4j.ollama.timeout=180s

  Ajuster la créativité du LLM (0.0 = déterministe, 1.0 = créatif) :
    quarkus.langchain4j.ollama.chat-model.temperature=0.3

  Nombre de résultats RAG retournés (dans RagConfiguration.java) :
    .maxResults(5)   → EmbeddingStoreContentRetriever

  Seuil de similarité cosinus minimum (dans RagConfiguration.java) :
    .minScore(0.5)   → valeur entre 0.0 et 1.0

--------------------------------------------------------------------------------
  MODÈLES OLLAMA — COMPARATIF
--------------------------------------------------------------------------------

  Modèle       RAM requise  Qualité  Vitesse  Notes
  ---------    -----------  -------  -------  ---------------------------------
  phi3         ~2 Go        ***      *****    Idéal pour machines limitées
  mistral      ~4 Go        ****     ****     Bon équilibre (recommandé)
  llama3       ~5 Go        *****    ***      Meilleure qualité générale
  gemma2       ~5 Go        *****    ***      Excellent en français/multilangue
  llama3:70b   ~40 Go       ******   *        Nécessite GPU puissant

================================================================================
