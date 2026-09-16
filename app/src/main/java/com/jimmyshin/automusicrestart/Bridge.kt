package com.jimmyshin.automusicrestart

import android.content.ComponentName
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.IBinder
import rikka.shizuku.Shizuku
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class Bridge : AutoCloseable {
    companion object {
        private val operation = java.util.concurrent.locks.ReentrantLock()
        fun ready(): Boolean = runCatching {
            Shizuku.pingBinder() && Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED
        }.getOrDefault(false)
    }
    private var locked = false
    private val args = Shizuku.UserServiceArgs(ComponentName(Store.context, PrivilegedControl::class.java))
        .daemon(false).processNameSuffix("music_control").version(BuildConfig.CONTROL_SERVICE_VERSION)
    private val latch = CountDownLatch(1)
    @Volatile private var remote: IControl? = null
    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName, service: IBinder) {
            remote = IControl.Stub.asInterface(service); latch.countDown()
        }
        override fun onServiceDisconnected(name: ComponentName) { remote = null }
    }
    fun connect(): IControl {
        check(operation.tryLock(15, TimeUnit.SECONDS)) { "다른 음악 제어 작업이 끝나지 않았습니다" }
        locked = true
        try {
            check(ready()) { "Shizuku가 실행 중이 아니거나 권한이 없습니다" }
            Shizuku.bindUserService(args, connection)
            check(latch.await(12, TimeUnit.SECONDS)) { "Shizuku 연결 시간 초과" }
            val control = checkNotNull(remote) { "Shizuku 연결이 끊겼습니다" }
            ServiceIdentity.verify(BuildConfig.CONTROL_BUILD_ID, { control.implementationId }, {
                check(ready()) { "Shizuku 권한 또는 연결 상실" }
                check(remote === control && control.asBinder().isBinderAlive) { "제어 서비스 연결 단절" }
            }, Store::log)
            return control
        } catch (failure: Exception) {
            close(destroy = true)
            throw failure
        }
    }
    override fun close() = close(destroy = false)
    fun close(destroy: Boolean) {
        if (!locked) return
        try { runCatching { Shizuku.unbindUserService(args, connection, destroy) } }
        finally { locked = false; operation.unlock() }
    }
}
