package com.ymt.garminrunoverlay

import android.app.Activity
import android.graphics.Color
import android.os.Bundle
import android.text.method.ScrollingMovementMethod
import android.view.Gravity
import android.widget.TextView

class PermissionsRationaleActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val text = TextView(this).apply {
            setBackgroundColor(Color.WHITE)
            setTextColor(Color.rgb(25, 35, 50))
            textSize = 16f
            setPadding(48, 56, 48, 56)
            gravity = Gravity.TOP
            movementMethod = ScrollingMovementMethod()
            this.text = """
                Garmin Run Overlay · Health Connect 데이터 이용 안내

                이 앱은 러닝 분석을 위해 Health Connect에서 운동 세션, 심박수, 속도, 거리, 걸음 cadence 데이터를 읽습니다.

                읽은 데이터는 사용자가 로그인한 Garmin Run Overlay 계정에 러닝 기록으로 저장되어 날짜별 그래프와 최대 10회 중첩 비교에 사용됩니다.

                이 앱은 Health Connect에 건강 데이터를 쓰거나 수정하지 않습니다. 사용자는 Android의 Health Connect 설정에서 언제든지 접근 권한을 취소할 수 있습니다.

                앱에서 가져온 러닝 데이터는 사용자가 앱 내 삭제 기능으로 제거할 수 있습니다.
            """.trimIndent()
        }
        setContentView(text)
    }
}
