# Morphe 음악 자동 복구

Morphe YouTube Music의 재생 시작을 감지해, 현재 음악 프로세스에 화면 초기화 이력이 없으면 버퍼링이 생기기 전에 한 번 재시작하는 Android 보조 앱입니다. 위젯·이어폰·Android Auto 재생에 같은 정책을 적용합니다. 음악 앱 APK와 설정은 수정하지 않습니다.

## 자동 실행 정책

| 현재 음악 프로세스 판별 | 동작 |
|---|---|
| Activity 또는 화면 표시 이력 있음 | 예방 재시작 생략 |
| 이력 없음 | 1초 간격 재확인 후 예방 재시작 |
| 이력 판별 불가, 프로세스 식별 가능 | 연속 10초 BUFFERING일 때 한 번 복구 |
| 프로세스 식별 불가·Shizuku 미준비·복수 미디어 세션 | 자동 실행 보류, 사유 표시 |

재생 상태가 PLAYING 또는 BUFFERING일 때만 판별합니다. 알림·세션만 있거나 일시정지한 상태에서는 실행하지 않습니다. 처음 두 곡이나 정확히 60초 위치로 제한하지 않습니다. 이력 불명확 시 버퍼링 시간은 곡 변경·세션 교체·일시정지·자연 복구 때 초기화합니다.

Shizuku에서 시스템 프로세스 진단의 `historicalHostingComponentTypes`와 `hasShownUi=true`를 함께 읽습니다. Galaxy Z Fold6 / Android 16에서는 실제 음악 화면을 열어도 Activity 비트가 누락되는 사례를 확인해, 프로세스 수명 동안 유지되는 화면 표시 이력을 보완 근거로 사용합니다. 이력은 특정 Morphe 초기화 메서드의 성공을 보증하지 않습니다. 필드나 프로세스 식별값을 읽지 못하면 이력 없음으로 간주하지 않습니다.

이 방식은 서비스 경로의 시작에서 문제가 생긴다는 기기 재현 결과를 예방 정책으로 적용합니다. 모든 Morphe 버전·기기에서 같은 원인이거나 항상 실패한다는 뜻은 아닙니다.

## 사용 준비

1. Shizuku 13.6.0 이상을 설치하고 자체 무선 디버깅 페어링과 자동 시작 설정을 완료합니다. PC ADB 페어링과 Shizuku 페어링은 별개입니다.
2. 보조 앱에서 Shizuku 권한과 알림 접근을 허용하고 배터리 제한을 해제합니다.
3. `자동화 사용`을 켭니다. 차량 연결 알림을 등록할 필요가 없습니다. 이미 재생 중이면 현재 세션부터 판별합니다.
4. 위젯 또는 이어폰으로 재생하고 최근 기록에서 판별·실행 결과를 확인합니다.

기존 자동화 스위치 상태는 업데이트해도 유지됩니다. 예전 차량 알림 등록 데이터는 복원용으로 남겨두지만 새 방식에서 읽거나 실행 조건으로 사용하지 않습니다. 삼성 모드의 기존 자동화는 별개이며, 이 앱이 수정하지 않습니다.

재부팅 후 최초 잠금 해제와 신뢰하는 Wi-Fi 연결이 필요할 수 있습니다. Shizuku가 준비되지 않으면 음악을 조작하지 않고 대기합니다. 리스너 재연결과 Shizuku 권한·연결 복구 시 현재 세션을 다시 판별합니다. 차량 네트워크만 사용하는 조건의 준비·유지 여부는 별도 검증 대상입니다.

## 복구 순서와 중단

자동 실행은 별도 예약 대기 없이 다음 기존 순서를 사용합니다.

**재생 요청 → 5초 대기 → 강제 종료 → 2초 대기 → 가상 화면 초기화 → 재생 확인**

- 대상은 `app.morphe.android.apps.youtube.music`으로 고정됩니다. 다른 앱에 재생 키를 보내지 않습니다.
- 종료 직전에 프로세스 식별값·Activity 이력·세션·곡·재생 상태를 재확인합니다. 사용자가 음악 화면을 열거나 곡을 바꾸거나 일시정지하면 불필요한 종료를 취소합니다. 버퍼링 복구는 자연 복구돼도 취소합니다.
- Shizuku의 `am force-stop --user 0`으로 대상만 종료하고, 이전 PID·세션이 사라졌는지 확인합니다.
- 실제 디스플레이에 연결되지 않은 임시 가상 화면에서 MusicActivity를 약 6초 초기화합니다. 프레임은 내부 버퍼에서 소비하고 저장·송출하지 않습니다. 기본 화면으로 우회하지 않습니다.
- 새 재생 세션을 확인하고 가상 화면을 제거합니다. 비교할 정보가 있으면 현재 곡이 같은지 검사합니다. 다음 곡 목록 재구성은 허용하며, 재생 위치는 처음으로 돌아갈 수 있습니다.
- 자동화 끄기·사용자 취소·이어폰 분리 신호·권한 상실 때 중단합니다. 강제 종료 뒤 중단되면 음악이 정지한 채 남을 수 있습니다. Android Auto 연결 여부 자체는 실행·중단 조건이 아닙니다.

프로세스는 부팅 식별값·PID·시작 식별값으로 구분합니다. 연결 준비 전에 자동 시도를 저장하고, 복구로 생성된 프로세스에도 같은 시도 제한을 적용합니다. 연결 준비 실패도 해당 프로세스의 시도로 기록하며, 복구 실패 후 반복 재시작하지 않습니다. 실패 알림은 소리·진동 없는 상태 알림입니다. 보조 앱 비정상 종료로 후속 프로세스를 특정하지 못하면 첫 관찰 프로세스를 차단하고, 이후 프로세스 교체 또는 수동 시험으로 해제합니다.

감시는 미디어 세션 콜백을 사용합니다. 상시 logcat 수집이나 짧은 주기의 전체 프로세스 조회는 하지 않습니다. 이력 확인·버퍼링 대기·복구에만 제한 시간의 wake lock을 사용합니다. 최근 기록은 앱 내부에 최대 16,000자만 저장하며, 곡명·전체 시스템 진단·개인정보를 서버로 보내지 않습니다.

## 수동 시험

`15초 후 시험 (화면을 잠그세요)`은 자동화가 꺼져 있어도 실행합니다. 15초 후 기존 복구 순서를 수행하며 실제 소리가 날 수 있습니다. `시험 중단`으로 취소할 수 있습니다.

완료 기록은 새 세션이 재생 상태가 됐다는 의미입니다. 실제 청취·장시간 재생·실차 검증 결과와 구분합니다. 검증 결과는 [VALIDATION.md](VALIDATION.md), 이전 장애 조사와 가상 화면 방식의 근거는 [INVESTIGATION.md](INVESTIGATION.md)에 있습니다.

## 빌드와 개발

Android 13 이상, Kotlin / Android Gradle Plugin 9.2.1 / Gradle 9.4.1 / SDK 36을 사용합니다.

프로젝트 밖 `$env:USERPROFILE/.android-auto-music-restart-signing/signing.properties`에 개인 서명을 설정합니다. `storeFile`, `storePassword`, `keyAlias`, `keyPassword`가 필요합니다. 키와 비밀번호를 저장소에 넣지 마세요.

```powershell
./build.ps1
```

설치 APK는 `app/build/outputs/apk/release/app-release.apk`, 기기 시험 전용 APK는 `app/build/outputs/apk/androidTest/release/app-release-androidTest.apk`입니다. 앱 ID는 `com.jimmyshin.automusicrestart`를 유지합니다. 일반 설치 APK에는 외부 시험 진입점을 추가하지 않습니다. AIDL 기존 트랜잭션 0·1·2는 유지하고 3에 대상 이력 조회를 추가했습니다. Shizuku 제어 서비스의 IPC revision은 앱 버전과 별개로 관리합니다.

```powershell
adb -s DEVICE shell am instrument -w -e scenario history com.jimmyshin.automusicrestart.test/com.jimmyshin.automusicrestart.DeviceChecks
adb -s DEVICE shell am instrument -w -e scenario automaticService com.jimmyshin.automusicrestart.test/com.jimmyshin.automusicrestart.DeviceChecks
```

`history`는 이력을 조회합니다. `automaticService`는 대상 종료·서비스 재생·자동 복구를 실제 시험하고 자동화를 켭니다. 위젯을 누른 시험과 구분합니다. `automationOff`와 `automationOn`은 기기 검증용으로 자동화 설정을 바꿉니다. 시험 후 원래 설정을 복원해야 합니다. `pausePlayback`은 자동화를 끄고 대상 음악을 일시정지합니다. `probe`, `sequence`, `cancel`, `cancelInitialization`, `menuSequence`는 기존 수동 복구·중단 검증에 사용합니다.

Git Flow에 따라 개발 중 앱 버전을 올리지 않고, master 병합 단계에서만 변경합니다. 이번 개발 기준은 `1.0.1 / versionCode 2`입니다.
