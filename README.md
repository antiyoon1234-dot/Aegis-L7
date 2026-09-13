<div align="center">
  
# 🛡️ Aegis-L7
**The ultimate Layer 7 Anti-Bot & Zero-Day Exploit shield for Minecraft.**

[![Platform](https://img.shields.io/badge/Platform-Paper%201.20+-333333.svg?style=flat-square)](#)
[![Java](https://img.shields.io/badge/Java-17+-orange.svg?style=flat-square)](#)
[![Redis](https://img.shields.io/badge/Sync-Redis-dc382d.svg?style=flat-square)](#)
[![License](https://img.shields.io/badge/License-MIT-blue.svg?style=flat-square)](#)

Aegis-L7은 Paper 서버를 겨냥한 **지능형 L7 DDoS 공격, 대규모 봇넷(Botnet), 그리고 악성 크래시 클라이언트(NBT 폭탄, 패킷 스팸)**를 완벽하게 차단하기 위해 개발된 보안 플러그인입니다.

메모리 누수 없는 가상 맵 캡챠, AI 채팅 필터링, 그리고 움직임 휴리스틱 분석 기능을 제공하며, 완벽한 스레드 안전성(Thread-Safe)**을 보장합니다.

## 플러그인은 개발 단계에 있습니다 미확인된 오류가 있기떄문에 확인후 픽스중입니다 개발진이 발견하지 못한 버그는 이슈로 넘겨주세요.

</div>

---

## 📑 목차 (Table of Contents)
1. [핵심 기능 및 전체 모듈 설명](#-핵심-기능-및-전체-모듈-설명)
2. [시스템 아키텍처 및 최적화](#-시스템-아키텍처-및-최적화)
3. [명령어 및 권한](#-명령어-및-권한)
4. [설치 및 요구사항](#-설치-및-요구사항)

---

## 🚀 핵심 기능 및 전체 모듈 설명

Aegis-L7은 총 5가지의 방어 계층(Layer)으로 구성되어 서버를 철통같이 보호합니다.

### 🌐 1. 네트워크 및 접속 필터 (Network & Connection)
봇넷이 서버 인게임에 진입하기 전에 네트워크 단에서 1차적으로 차단합니다.
* **접속률 제한 (Connection Rate Limit):** 단시간 내에 비정상적으로 쏟아지는 IP 연결을 차단합니다.
* **Ping-Before-Join:** 클라이언트가 서버 리스트 핑(SLP)을 거치지 않고 강제로 다이렉트 접속을 시도하는 스크립트 봇을 차단합니다.
* **재접속 검증 (Rejoin Verification):** 봇넷 공격 특유의 동시 다발적 접속을 막기 위해, 첫 접속 시 킥(Kick) 처리 후 무작위 시간(3~15초) 대기 후 재접속하도록 유도합니다.
* **GeoIP & Anti-VPN:** 프록시나 VPN을 통한 접속을 차단하며, 특정 국가(예: 한국) 외의 접속을 공격 상황에서만 유동적으로 차단할 수 있습니다.
* **호스트네임 검증 (Hostname Validation):** 도메인이 아닌 IP:Port 숫자로 직접 치고 들어오는 포트 스캐너 및 무작위 봇넷 접속을 차단합니다.

### 🖼️ 2. 차세대 스마트 캡챠 (Smart Captchas)
뚫기 쉬운 기존 캡챠와 달리, 클라이언트 렌더링을 요구하여 콘솔 기반 봇을 무력화합니다.
* **Virtual Map OCR 캡챠 (Zero Leak):** 맵 캡챠 생성 시 발생하는 버킷의 영구적인 Map ID 메모리 누수를 완벽히 해결했습니다. 단 1개의 가상 뷰(MapView)만 캐싱하고, 유저별로 픽셀을 개별 렌더링합니다.
* **3D Entity 타격 캡챠:** 플레이어 시야 앞에 초록색 아머스탠드를 스폰시켜 정확히 에임하고 좌클릭하게 만듭니다. 타임아웃 및 엔티티 잔존 메모리 누수 방어 로직이 적용되어 있습니다.
* **GUI 인벤토리 캡챠:** 인벤토리 클릭 이벤트 조작(Ghost Item)을 방지하는 안전한 GUI 캡챠입니다.
* **서버 리소스팩 검증:** 인게임에 들어오기 전 서버 리소스팩을 수락(Accept)해야만 통과시킵니다.

### 🧠 3. AI 및 행동 휴리스틱 (AI & Heuristics)
진짜 플레이어와 기계(Bot)의 움직임 패턴과 행동을 수학적으로 분석합니다.
* **AI 채팅 필터 (Levenshtein & RTL Block):** 
  * 머신러닝의 문자열 편집 거리 알고리즘을 사용해 교묘하게 문자를 바꾼 도배를 차단합니다.
  * `\u202E` 등 클라이언트 채팅 렌더링을 터뜨리는 RTL 특수문자 익스플로잇을 원천 차단합니다.
* **기계적 시점 이동 감지 (Snap Aim Heuristic):** 1틱 만에 180도를 회전하거나 Pitch(위아래 각도)가 소수점 없이 0.0, 90.0으로 완벽히 고정되는 Baritone/봇넷의 움직임을 적발합니다.
* **오토마우스 및 킬아우라 감지 (Honeypot):** 
  * 투명한 박쥐(Honeypot)를 소환해 강제 타격을 유도하여 Killaura를 적발합니다.
  * 초당 클릭수(CPS)의 표준편차를 분석하여 인간이 낼 수 없는 완벽한 주기의 매크로를 차단합니다.

### ⚔️ 4. 제로데이 익스플로잇 및 크래셔 방어 (Exploit Crashers)
* **NBT 메모리 폭탄 방어:** 책(Book), 표지판(Sign)에 수만 글자의 NBT 데이터를 밀어넣어 서버 RAM을 터뜨리는 공격을 차단합니다.
* **조명 연산 크래셔 (Light Crasher):** 횃불, 발광석 등 조명 업데이트를 유발하는 블록을 Nuker로 초당 수백 번 부수어 서버 스레드 락(Lock)을 유발하는 행위를 차단합니다.
* **단일 패킷 크기 제한:** 25KB를 초과하는 비정상적인 플러그인 메시지(Custom Payload)를 차단합니다.
* **클라이언트 모드 스푸핑 방어:** WDL(World Downloader) 등 서버 맵을 무단 유출하는 모드의 통신 채널을 감지하여 밴합니다.
* **Ping Flood 역공격 최적화 (SLP Crasher):** 핑 폭발 공격을 하는 봇에게 기형적인 OOM 유발 페이로드를 반사합니다. (서버 CPU 점유율을 낭비하지 않도록 Static 캐싱 적용 완비)

### 🔗 5. 중앙 동기화 및 관리 (Sync & Admin)
* **Redis Pub/Sub 클러스터링:** 차단된 악성 IP를 Redis 채널을 통해 연결된 모든 BungeeCord 및 하위 서버에 0.1초 만에 실시간으로 전파합니다.
* **실시간 Admin GUI:** 인게임에서 `/l7def gui`를 통해 모듈의 상태를 확인하고 원클릭으로 켜고 끌 수 있습니다.

---

## 🛠 시스템 아키텍처 및 최적화 (Zero-Leak Architecture)

Aegis-L7은 초당 만 단위의 커넥션이 발생하는 극단적인 스트레스 환경을 상정하여 **완벽한 메모리/스레드 안전성**을 목표로 설계되었습니다.

* **100% 비동기 및 Thread-Safe:** Bukkit API의 한계를 우회하기 위해 `ConcurrentHashMap`, `ConcurrentLinkedQueue` 및 `Caffeine Cache`를 전면 도입했습니다. 비동기 로그인 환경에서 발생할 수 있는 `ConcurrentModificationException`을 불변(Immutable) 객체 디자인으로 차단했습니다.
* **서버 리로드 누수 원천 차단:** `/reload` 또는 `/l7def reload`를 50회 이상 반복해도 잔여 Task, Plugin Channel, Redis Daemon Thread가 100% 수거(Garbage Collected)되도록 완벽한 생명주기 관리 로직이 적용되어 있습니다. 
* **동기(Sync) 처벌 위임:** 비동기 채팅 스레드 등에서 유저를 차단(Kick)할 때 발생하는 버킷 크래시를 방지하기 위해, 모든 처벌 로직은 중앙 `SecurityManager`를 거쳐 Main Thread로 안전하게 위임됩니다.

---

## 📜 명령어 및 권한 (Commands & Permissions)

| 명령어 | 권한 (Permission) | 설명 |
|---|---|---|
| `/l7def gui` | `l7defense.admin` | 방어 모듈 관리 및 실시간 통계 창 열기 |
| `/l7def reload` | `l7defense.admin` | config.yml 설정을 메모리 누수 없이 실시간 핫 리로드 |
| `/l7def test <모듈>` | `l7defense.admin` | 봇 방어 모듈 정상 작동 시뮬레이션 및 테스트 |

*(OP 또는 `l7defense.admin` 권한 소지자는 모든 봇 방어 및 캡챠 검사를 자동으로 바이패스합니다.)*

---

## 📦 설치 및 요구사항 (Installation)

1. **Java 17 이상**, **Paper 1.20.x 이상**의 서버가 필요합니다.
2. 다운로드한 `Aegis-L7.jar` 파일을 서버의 `plugins` 폴더에 넣습니다.
3. 서버를 구동하여 `plugins/L7Defense/config.yml` 파일을 생성합니다.
4. (선택) Redis 동기화를 사용하려면 `config.yml`의 `endgame-features.redis-sync` 섹션에 호스트 정보를 입력하고 `/l7def reload`를 입력하세요.

---

## 📄 라이선스 (License)
이 프로젝트는 [MIT License](LICENSE)에 따라 배포됩니다. 자유롭게 포크하고 서버 방어에 기여해 주세요!
