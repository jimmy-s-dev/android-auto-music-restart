# 차량 음악 재시작

Morphe YouTube Music용 Android 보조 앱입니다. 무선 Android Auto 연결 알림을 감지하여 `5초 대기 → 5초 재생 → 강제 종료 → 2초 대기 → 재생`을 한 번 실행합니다. 버퍼링 원인 분석이나 패치 변경은 하지 않습니다.

## 사용 준비

1. Shizuku 13.6.0 이상을 설치하고 무선 디버깅으로 페어링합니다. PC의 ADB 페어링과 Shizuku 자체의 페어링은 별개입니다.
2. Shizuku 설정의 부팅 시 시작을 켭니다. 13.6.0에서는 한국어 메뉴에 `(루팅)`이 남아 있어도 비루팅 자동 시작 경로가 있습니다. 자동 시작에는 `WRITE_SECURE_SETTINGS` 권한과 신뢰하는 Wi-Fi가 필요합니다.
3. 보조 앱에서 Shizuku 권한과 알림 접근을 허용하고 배터리 제한을 해제합니다.
4. 앱의 `15초 후 시험`은 자동화 스위치가 꺼져 있거나 차량 알림이 미등록이어도 동작합니다. 실행 후 화면을 잠가 시험할 수 있습니다. 시험 중단 버튼으로 취소할 수 있습니다.

재부팅 후 최초 잠금 해제 및 신뢰하는 Wi-Fi 연결이 필요할 수 있습니다. 기기에 따라 부팅 알림 전달과 Shizuku 준비가 지연될 수 있습니다. Shizuku가 준비되지 않았다면 자동화는 실패 결과를 남기고 종료합니다. Wi-Fi 연결이 늦어지는 경우와 차량 네트워크만 사용하는 경우는 검증하지 않았습니다.

## 최초 차량 설정

정차한 차량에서 진행합니다.

1. 실제 무선 Android Auto 연결을 완료합니다.
2. 보조 앱에서 `차량 연결 알림 등록`을 누르고 **연결 완료를 나타내는 지속 알림**을 선택합니다. 연결 중·설정·업데이트 안내는 선택하지 않습니다.
3. `자동화 사용`을 켭니다. 이미 차량에 연결되어 있다면 이때 한 번 실행됩니다.
4. 폰 화면을 끈 채 3분 이상 재생을 확인하고, 차량 연결을 해제했다 다시 연결해 반복 시험합니다.

## 백그라운드 실행 방식

- 대상 패키지는 `app.morphe.android.apps.youtube.music`으로 고정되어 있습니다.
- 기존 대상 미디어 세션이 있으면 그 세션에 명시적인 `play()`를 전송합니다.
- 세션이 없으면 Shizuku UserService가 **대상 MusicBrowserService에만** `KEYCODE_MEDIA_PLAY`가 포함된 명시적 `MEDIA_BUTTON` 인텐트를 전달하여 포그라운드 **서비스**를 시작합니다. 포그라운드 서비스는 음악 알림을 가진 백그라운드 서비스이며 앱 화면(Activity)을 여는 것과 다릅니다.
- 그 뒤 대상 세션을 확보하여 `play()`를 전송하고 `STATE_PLAYING`을 확인합니다. 앱 화면을 여는 대체 동작, 전역 재생 키, 재생/일시정지 토글은 없습니다.
- 5초 재생 후 Shizuku에서 `am force-stop --user 0`으로 대상만 종료합니다. 기존 PID가 사라지고 이전 미디어 세션 토큰이 제거되었는지 확인합니다. Android Auto가 먼저 앱을 재시작하는 경우 새 PID는 허용하되 기존 PID는 허용하지 않습니다.
- 2초 후 같은 백그라운드 방식으로 새 세션의 재생을 확인합니다.

## 중복·실패 처리

Android Auto 패키지의 지속 알림만 처리하며, 등록한 알림의 패키지·ID·태그·채널·제목·본문을 SHA-256 식별값으로 비교합니다. 다른 앱의 알림 내용은 기록하지 않습니다. 알림 문구가 업데이트·언어 변경으로 달라지면 실차에서 다시 등록해야 합니다.

한 연결에서 한 번만 실행하고 이 상태를 저장하여 프로세스 재생성으로 중복 실행하지 않도록 합니다. 연결 알림이 3초 이상 사라지면 연결 해제로 처리합니다. 실행 중 연결 해제, 권한 부족, 시간 초과, 사용자 중단 시 반복 재시작 없이 종료합니다. 종료 직후 취소하면 음악이 정지한 상태로 남을 수 있습니다. 수동 시험은 차량 연결을 요구하지 않습니다.

실패하면 앱 알림과 최근 기록으로 확인할 수 있습니다. 기록은 앱 내부에 최대 16,000자만 저장하며 서버로 보내지 않습니다. 완료 기록은 새 세션의 재생 확인이며, 차량에서의 버퍼링 해결 확인은 별도입니다.

## 빌드 및 파일

프로젝트는 Kotlin, Android Gradle Plugin 9.2.1, Gradle 9.4.1, Android SDK 36을 사용합니다. Android 13 이상을 대상으로 하며 과거 기기 검증은 Android 16에서 수행했습니다.

Windows PowerShell에서:

```powershell
./build.ps1
```

릴리스 빌드 전에 프로젝트 밖 `$env:USERPROFILE/.android-auto-music-restart-signing/signing.properties`에 본인의 서명 설정을 준비하세요. Gradle은 사용자 홈 디렉터리에서 이 파일을 읽습니다. `storeFile`에는 본인 키 파일의 절대 경로를 슬래시(`/`)로 지정하고, `storePassword`, `keyAlias`, `keyPassword`를 설정합니다. 실제 키·비밀번호·설정 파일은 커밋하거나 배포하지 마세요. 키를 잃으면 같은 앱 ID로 기존 앱을 업데이트할 수 없습니다.

앱 ID는 `com.jimmyshin.automusicrestart`입니다.

`app/build/outputs/apk/release/app-release.apk`가 설치용 APK입니다. `app-release-androidTest.apk`는 기기 검증 전용 APK이며 일반 사용에 필요하지 않습니다. 테스트용 실행 진입점은 설치용 앱에 노출하지 않습니다.

기기 시험은 동일 서명 테스트 APK 설치 후 명시적인 ADB 시리얼로 수행합니다:

```powershell
adb -s DEVICE shell am instrument -w -e scenario probe com.jimmyshin.automusicrestart.test/com.jimmyshin.automusicrestart.DeviceChecks
adb -s DEVICE shell am instrument -w -e scenario sequence com.jimmyshin.automusicrestart.test/com.jimmyshin.automusicrestart.DeviceChecks
adb -s DEVICE shell am instrument -w -e scenario cancel com.jimmyshin.automusicrestart.test/com.jimmyshin.automusicrestart.DeviceChecks
```

`probe`는 상태만 읽습니다. `sequence`는 실제 재생·종료·재생을 수행하며 소리가 날 수 있습니다. `cancel`은 시작 대기 중 중복 요청과 취소를 시험합니다.

## 중지 및 제거

자동화 스위치를 끄면 새 자동 실행을 막고 진행 중 실행도 취소합니다. 보조 앱을 삭제하면 해당 앱의 알림 접근과 실행 기록도 제거됩니다. Shizuku는 다른 앱에서 사용할 수 있으므로 보조 앱 삭제 시 함께 제거하지 않습니다. 원래 YouTube Music의 데이터는 지우지 않습니다.

## 참고

- [Shizuku 13.6.0 변경 사항](https://github.com/RikkaApps/Shizuku/releases/tag/v13.6.0)
- [Shizuku API / UserService](https://github.com/RikkaApps/Shizuku-API)
- [Android NotificationListenerService](https://developer.android.com/reference/android/service/notification/NotificationListenerService)

실제 확인 결과와 남은 검증은 `VALIDATION.md`를 확인하세요.
