# ClearPDF Local

> **인터넷 권한 없는 완벽한 보안, 가장 빠르고 강력한 오프라인 PDF 리더 & 문서 스캐너**
> 
> ClearPDF Local은 외부 서버 통신을 차단하고 100% 로컬 환경에서 소중한 문서를 열람, 병합, 분할, 촬영 스캔할 수 있도록 설계된 Android 애플리케이션입니다.

ClearPDF Local is a secure, local-first, and lightweight offline PDF utility app for Android, built with Kotlin and Jetpack Compose. Read documents, scan paper bills via camera, merge files, and split pages fully on-device with zero network requests.

---

<p align="center">
  <img src="docs/assets/key-visual.png" alt="ClearPDF Local Key Visual" width="700">
</p>

---

## 🔒 핵심 가치 & 브랜딩 (Core Values)

- **보안 중심의 로컬 전용 (Purely Local)**: 인터넷 통신 권한 (`android.permission.INTERNET`) 자체를 선언하지 않아, 기기에 보관 중이거나 스캔한 소중한 문서 정보가 기기 외부나 외부 클라우드로 업로드될 가능성을 근본적으로 차단합니다.
- **고성능 이미지 보정 (Smart Camera Scanner)**: 기기 내장 카메라로 종이 문서나 영수증을 촬영하면 OpenCV 기반 온디바이스 파이프라인이 문서의 가장자리를 자동 검출하고, 투영 원근 보정으로 비스듬한 사진을 반듯하게 펴며, 그림자·조명 얼룩을 정리합니다. 페이지마다 자동(매직 컬러)·컬러·회색조·흑백 보정 모드를 선택할 수 있습니다.
- **자동 스캔 (Auto-Scan)**: 카메라를 문서 위에 가만히 들고 있으면 실시간으로 테두리를 인식해 알아서 촬영합니다. 책을 넘기면 다음 장이 연달아 스캔되어, 셔터를 누르지 않고도 여러 페이지를 빠르게 담을 수 있습니다. 자동/수동은 한 번의 탭으로 전환됩니다.
- **검색 가능 PDF / 글자 인식 (On-Device OCR)**: 스캔을 저장할 때 'Searchable PDF (OCR)'를 켜면 Tesseract 기반 온디바이스 OCR이 한국어·영어 글자를 인식해 PDF에 투명 텍스트 레이어를 입힙니다. 결과 PDF는 검색·선택·복사가 가능하며, 학습 데이터는 앱에 번들되어 인터넷 없이 동작합니다. 인식이 어려운 페이지는 자동으로 일반 PDF로 폴백합니다.
- **다재다능한 문서 도구 (PDF Toolbox)**: 병합 (Merge), 분할 (Split), 이미지 변환 (Image-to-PDF) 등 문서 작업에 꼭 필요한 필수 도구들을 하나의 가볍고 빠른 유틸리티로 담았습니다.

---

## 🛠️ 기술 스택 (Tech Stack)

| 영역 | 선택 기술 |
|---|---|
| **언어 (Language)** | Kotlin |
| **UI 프레임워크** | Jetpack Compose, Material 3 |
| **아키텍처 (Architecture)** | Clean Architecture / MVVM 패턴 |
| **로컬 데이터베이스** | Room Database |
| **비동기 처리** | Kotlin Coroutines & Flow |
| **이미지 보정 및 뷰어** | Android CameraX API, Android PdfRenderer |
| **빌드 구성** | Gradle Kotlin DSL + 버전 카탈로그 (`libs.versions.toml`) |

---

## 📦 저장소 구조 (Directory Structure)

```text
clear-pdf-local/
  app/
    src/main/
      java/com/jeiel85/clearpdflocal/
        data/         # Room Database, 파일 입출력 및 리포지토리 레이어
        domain/       # PDF 변환, 병합, 분할 핵심 유스케이스 로직
        ui/
          screens/    # 리더, 스캐너, 도구(병합/분할) 등 화면 구성
          theme/      # Dark Slate & Cyan 디자인 테마
          viewmodel/  # 상태 제어 및 비즈니스 조율 ViewModel
      res/            # 다국어 스트링, 벡터 아이콘 및 스타일 리소스
      AndroidManifest.xml
  docs/               # GitHub Pages 랜딩 웹페이지 및 그래픽 자산
  store-graphics/     # Play Store 등록용 이미지 리소스 (512px 아이콘, 배너)
```

---

## 🚀 빠른 시작 (Quick Start)

### 빌드 및 실행 요구조건
- **JDK**: 17 이상
- **Android SDK**: Platform 36
- **Android Build Tools**: 36.0.0
- **Android Studio**: Ladybug 이상 권장

### 빌드 및 로컬 테스트 방법
Windows PowerShell 또는 Linux/macOS 터미널에서 다음 명령어를 실행하여 로컬 빌드 및 통합 테스트를 실행할 수 있습니다.

**Windows PowerShell**:
```powershell
# 단위 테스트 및 Robolectric 스크린샷 검증 테스트 수행
.\gradlew.bat test

# 디버그 개발용 APK 컴파일 및 설치
.\gradlew.bat assembleDebug
```

**Linux / macOS**:
```bash
./gradlew test
./gradlew assembleDebug
```

---

## 🔐 릴리즈 빌드 및 서명 정책 (Release & Signing)

배포용 AAB(Android App Bundle) 파일을 빌드할 때는 보안 서명을 적용해야 합니다. 로컬 디렉토리에 서명 자격증명을 작성하여 안전하게 빌드할 수 있습니다.

1. 프로젝트 루트에 `.keystore` 디렉토리를 생성하고, 서명용 `my-upload-key.jks` 키 파일을 배치합니다.
2. 프로젝트 루트에 `.env` 파일을 생성하고 아래와 같이 키 정보를 기재합니다.
```env
KEYSTORE_PATH=.keystore/my-upload-key.jks
STORE_PASSWORD=your_password
KEY_ALIAS=upload
KEY_PASSWORD=your_password
```
3. 다음 Gradle 명령어를 입력하여 빌드 및 데스크톱 내보내기를 동시 수행합니다:

```powershell
# 배포용 릴리즈 AAB 빌드 및 Desktop/Build 폴더로 즉시 복사 내보내기
.\gradlew.bat :app:exportReleaseToDesktop
```

---

## 📄 라이선스 (License)
본 프로젝트는 **MIT License**에 따라 자유롭게 복제, 배포 및 수정할 수 있습니다.
