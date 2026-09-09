# 차량 음악 재시작

Morphe YouTube Music용 Android 보조 앱입니다. 무선 Android Auto 연결 알림을 감지하여 `5초 대기 → 재생 요청 후 5초 대기 → 강제 종료 → 2초 대기 → 재생 확인`을 한 번 실행합니다. 강제 종료 후 보이지 않는 임시 가상 화면에서 음악 앱을 초기화하여, 서비스만 시작할 때 빠지는 Activity 초기화 경로를 실행합니다. 음악 앱 APK와 설정은 수정하지 않습니다.

## 사용 준비

1. Shizuku 13.6.0 이상을 설치하고 무선 디버깅으로 페어링합니다. PC의 ADB 페어링과 Shizuku 자체의 페어링은 별개입니다.
2. Shizuku 설정의 부팅 시 시작을 켭니다. 13.6.0에서는 한국어 메뉴에 `(루팅)`이 남아 있어도 비루팅 자동 시작 경로가 있습니다. 자동 시작에는 `WRITE_SECURE_SETTINGS` 권한과 신뢰하는 Wi-Fi가 필요합니다.
3. 보조 앱에서 Shizuku 권한과 알림 접근을 허용하고 배터리 제한을 해제합니다.
4. 앱의 `15초 후 시험`은 자동화 스위치가 꺼져 있거나 차량 알림이 미등록이어도 동작합니다. 최초 대기만 15초이며, 이후에는 `재생 요청 → 5초 대기 → 강제 종료 → 2초 대기 → 재생 확인`을 수행합니다. 실행 후 화면을 잠가 시험할 수 있습니다. 시험 중단 버튼으로 취소할 수 있습니다.

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
- 첫 요청에서는 `STATE_PLAYING`을 기다리지 않습니다. 버퍼링이 지속되거나 아직 세션이 생성되지 않아도 요청 후 5초가 지나면 종료 단계로 진행합니다. 권한·서비스 시작 오류와 취소는 즉시 중단합니다.
- Shizuku에서 `am force-stop --user 0`으로 대상만 종료합니다. 종료 직전 수집한 모든 PID와 미디어 세션 토큰이 제거되었는지 확인합니다. Android Auto가 먼저 앱을 재시작하는 경우 새 PID는 허용하되 기존 PID는 허용하지 않습니다.
- 2초 후 Shizuku가 임시 가상 화면을 만들고 그 화면에서만 MusicActivity를 약 6초 초기화합니다. Surface는 보조 앱의 내부 이미지 버퍼에만 연결됩니다. 음악 앱이 지정 화면 밖으로 이동하면 대상만 종료하고 실패 처리하며, 기본 화면으로 다시 시도하지 않습니다.
- 이어서 대상 재생 서비스를 시작하고 새 세션의 PLAYING을 확인한 뒤 가상 화면과 Activity를 제거합니다. 세션 생성과 재생 확인 제한은 각각 15초입니다. 기존 세션의 곡을 읽을 수 있으면 재시작 전후 현재 곡이 같은지 확인하며, 다르면 일시정지하고 실패 처리합니다. 사용자가 허용한 대로 모르페의 다음 곡 목록 재구성은 허용하고 기록합니다. 기존 정보가 없으면 비교 불가로 기록합니다. 세션 내부 항목 번호는 재시작으로 달라지므로 내용 비교에서 제외합니다.
- 초기화 중 취소·권한 상실·자동 실행의 차량 연결 해제는 호출을 중단하고 Shizuku UserService를 닫습니다. 가상 화면은 정상 종료·오류·프로세스 종료 때 정리됩니다. 전역 재생 키와 재생/일시정지 토글은 사용하지 않습니다.
- 이 방식은 사용자가 승인한 Activity 초기화 방식이며, 이전의 서비스 전용 방식과 내부 구현이 다릅니다. 재생 위치는 음악 앱의 복원 동작에 따라 현재 곡 처음으로 돌아갈 수 있습니다. 기기·OS에 필요한 가상 화면 기능이 없으면 실패로 끝납니다.

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

`probe`는 상태만 읽습니다. `sequence`는 실제 재생·종료·재생을 수행하며 소리가 날 수 있습니다. `cancel`은 시작 대기 중 중복 요청과 취소를 시험합니다. `cancelInitialization`은 실제 가상 화면이 생성된 뒤 취소하고 화면 제거를 검증합니다. `menuSequence`는 보조 앱 Activity의 실제 `15초 후 시험` 버튼에 instrumentation으로 클릭을 전달하고, 재생 복구 뒤 같은 곡을 185초 관찰합니다. 물리적 화면 터치와 실차 시험을 대신한 것으로 해석하지 않습니다.

추가 진단 APK의 `mediaProbe`는 재생 상태와 곡·목록 식별값의 해시를 읽고, `observePlayback`은 185초 동안 상태와 위치를 관찰합니다. `recoverBuffering`은 같은 세션에서 `pause → seekTo(0) → play`를 한 번 시험합니다. `reloadCurrentItem`은 현재 목록 항목 재선택을 지원할 때만 같은 항목에 요청합니다. 두 복구 시험은 실제 재생을 조작하므로 재현 상태를 보존해야 할 때 실행하지 마세요. 이 방법들은 차량 자동 실행에 연결하지 않았으며, 후속 실패 결과와 기기 설정 변경 내역은 [INVESTIGATION.md](INVESTIGATION.md)에 기록했습니다.

일시정지 조건 준비에는 동일 실행기의 `-e scenario pauseTarget`을 사용할 수 있습니다. 대상 패키지의 세션에만 일시정지를 요청하고 `PAUSED`를 확인합니다. 이 준비가 실패하면 일시정지 조건 시험으로 판정하지 않습니다.

## 중지 및 제거

자동화 스위치를 끄면 새 자동 실행을 막고 진행 중 실행도 취소합니다. 보조 앱을 삭제하면 해당 앱의 알림 접근과 실행 기록도 제거됩니다. Shizuku는 다른 앱에서 사용할 수 있으므로 보조 앱 삭제 시 함께 제거하지 않습니다. 원래 YouTube Music의 데이터는 지우지 않습니다.

## 참고

- [Shizuku 13.6.0 변경 사항](https://github.com/RikkaApps/Shizuku/releases/tag/v13.6.0)
- [Shizuku API / UserService](https://github.com/RikkaApps/Shizuku-API)
- [Android NotificationListenerService](https://developer.android.com/reference/android/service/notification/NotificationListenerService)

실제 확인 결과와 남은 검증은 `VALIDATION.md`를 확인하세요.

## 버전 관리

Git Flow에 따라 개발 브랜치에서는 `master`의 버전(`1.0.0`, `versionCode = 1`)을 유지합니다. 버전은 `master`에 병합할 때만 올립니다. 개발 중 검증은 기능·시험 단계로 구분합니다.
