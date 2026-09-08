# BM Pay API (`bm-pay-api`)

Backend Ktor (Kotlin) pour **Build-Main**, plateforme SaaS de collecte de dîmes, offrandes et dons pour églises en Côte d'Ivoire. Intégration unifiée avec la passerelle Mobile Money **Jèko** (Wave, Orange Money, MTN MoMo, Moov Money).

---

## 🛠️ Stack Technique

- **Langage** : Kotlin 2.1
- **Framework** : Ktor 3.x (Netty Engine, CIO Client)
- **Base de données / ORM** : SQLite (local) / PostgreSQL (dev/prod), Exposed ORM, HikariCP
- **Sérialisation** : kotlinx.serialization (JSON)
- **Temps Réel** : WebSockets (projection Live Giving)
- **Sécurité** : Vérification de signature HMAC-SHA256 des webhooks Jèko

---

## 🚀 Démarrage Rapide

### 1. Prérequis
- Java JDK 17+
- Gradle (ou utiliser `./gradlew`)

### 2. Configuration
Copier le fichier d'exemple et renseigner vos clés :
```bash
cp .env.example .env.local
```

### 3. Lancer le serveur
```bash
./gradlew run
```
Le serveur démarre par défaut sur `http://localhost:8080`.

---

## 📡 Endpoints Principaux

- `POST /api/donations/initiate` : Initialisation d'un don / collecte via Jèko.
- `POST /api/webhooks/jeko` : Réception et validation HMAC-SHA256 des notifications de paiement Jèko.
- `WS /ws/live/{churchSlug}` : Flux WebSocket temps réel pour l'écran de projection au temple.
- `GET /api/churches/{slug}` : Informations publiques de la paroisse / église.
- `GET /api/dashboard/{slug}/stats` : Statistiques de collecte (dîmes, offrandes, etc.).
