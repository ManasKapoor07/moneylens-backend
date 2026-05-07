# MoneyLens Backend — Sprint 1: Authentication

> Spring Boot 3.2 · Java 21 · JWT · Spring Security · JPA

---

## 🏗️ Architecture

```
moneylens-backend/
├── src/main/java/com/moneylens/
│   ├── MoneyLensApplication.java
│   ├── config/
│   │   └── SecurityConfig.java          # CORS, JWT filter chain, route protection
│   ├── controller/
│   │   └── AuthController.java          # All auth endpoints
│   ├── dto/
│   │   ├── request/                     # RegisterRequest, LoginRequest, etc.
│   │   └── response/                    # AuthResponse, ApiResponse<T>
│   ├── entity/
│   │   ├── User.java                    # User entity with roles, verification
│   │   └── RefreshToken.java            # Refresh token with rotation support
│   ├── exception/
│   │   └── GlobalExceptionHandler.java  # Clean JSON error responses
│   ├── repository/
│   │   ├── UserRepository.java
│   │   └── RefreshTokenRepository.java
│   ├── security/
│   │   ├── JwtUtil.java                 # Token generation & validation
│   │   ├── JwtAuthenticationFilter.java # Per-request JWT validation
│   │   └── UserDetailsServiceImpl.java  # Load user from DB
│   └── service/
│       ├── AuthService.java             # Core auth logic
│       └── EmailService.java            # Verification & reset emails
└── src/main/resources/
    └── application.yml                  # Dev (H2) + Prod (PostgreSQL) profiles
```

---

## 🔐 Auth Flow

```
REGISTER
  POST /api/v1/auth/register
  → Validates email uniqueness
  → Hashes password (BCrypt 12 rounds)
  → Creates user + (optionally) sends verification email
  → Returns accessToken + refreshToken

LOGIN
  POST /api/v1/auth/login
  → Authenticates via Spring Security
  → Updates lastLogin timestamp
  → Cleans up expired refresh tokens
  → Returns accessToken + refreshToken

PROTECTED REQUEST
  GET /api/v1/... (Authorization: Bearer <accessToken>)
  → JwtAuthenticationFilter validates token
  → Sets SecurityContext
  → Request proceeds

REFRESH
  POST /api/v1/auth/refresh { refreshToken }
  → Validates token exists, not revoked, not expired
  → ROTATES token (old one immediately invalidated)
  → Returns new accessToken + new refreshToken
  → If stale token used → revokes ALL user sessions (breach protection)

LOGOUT
  POST /api/v1/auth/logout       → Revokes single refresh token
  POST /api/v1/auth/logout-all   → Revokes all sessions (all devices)
```

---

## 📡 API Endpoints

| Method | Endpoint | Auth | Description |
|--------|----------|------|-------------|
| POST | `/api/v1/auth/register` | ❌ | Register new user |
| POST | `/api/v1/auth/login` | ❌ | Login, get tokens |
| POST | `/api/v1/auth/refresh` | ❌ | Rotate refresh token |
| POST | `/api/v1/auth/logout` | ❌ | Revoke refresh token |
| POST | `/api/v1/auth/logout-all` | ✅ | Revoke all sessions |
| GET  | `/api/v1/auth/verify-email?token=` | ❌ | Verify email |
| POST | `/api/v1/auth/forgot-password` | ❌ | Send reset email |
| POST | `/api/v1/auth/reset-password` | ❌ | Reset via token |
| POST | `/api/v1/auth/change-password` | ✅ | Change password |
| GET  | `/api/v1/auth/me` | ✅ | Validate token |

---

## 🚀 Running Locally

```bash
# 1. Clone and navigate
cd moneylens-backend

# 2. Run with H2 (no setup needed)
mvn spring-boot:run

# 3. H2 Console (dev only)
open http://localhost:8080/h2-console
# JDBC URL: jdbc:h2:mem:moneylensdb

# 4. Test the API
curl -X POST http://localhost:8080/api/v1/auth/register \
  -H "Content-Type: application/json" \
  -d '{
    "fullName": "Manas Kumar",
    "email": "manas@example.com",
    "password": "SecurePass123"
  }'
```

---

## 🐘 Production (PostgreSQL)

```bash
# Set environment variables
export DATABASE_URL=jdbc:postgresql://localhost:5432/moneylens
export DATABASE_USERNAME=postgres
export DATABASE_PASSWORD=yourpassword
export JWT_SECRET=your-256-bit-secret-key-here
export MAIL_USERNAME=noreply@moneylens.com
export MAIL_PASSWORD=your-smtp-password
export FRONTEND_URL=https://moneylens.app

# Run with prod profile
mvn spring-boot:run -Dspring-boot.run.profiles=prod
```

---

## 🔒 Security Decisions

| Decision | Why |
|----------|-----|
| BCrypt strength 12 | ~300ms hash time — good brute-force protection |
| Short JWT expiry (24h) | Limit blast radius if token leaked |
| Refresh token rotation | Old token invalid immediately after use |
| Breach detection | Stale refresh token used → all sessions revoked |
| Email enumeration prevention | Forgot password always returns 200 |
| STATELESS sessions | No server-side session state |
| CORS configured | Only allows known frontend origins |

---

## 📦 Sprint 2 Preview

- [ ] User profile endpoint (`GET /api/v1/users/me`)
- [ ] Bank statement upload (`POST /api/v1/statements/upload`)
- [ ] PDF parser service (Apache PDFBox)
- [ ] Transaction entity + repository
- [ ] Basic categorisation engine

---

*Built with ❤️ for MoneyLens — India's smartest expense analyser*
