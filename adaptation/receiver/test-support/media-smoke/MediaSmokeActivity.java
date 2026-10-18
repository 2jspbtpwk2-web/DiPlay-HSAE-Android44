package local.airuize.diplaylegacy.mediasmoke;

import android.app.Activity;
import android.os.Bundle;
import android.view.Surface;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.view.WindowManager;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/** A real display Surface owned entirely by the independent test application. */
public final class MediaSmokeActivity extends Activity implements SurfaceHolder.Callback {
    private final CountDownLatch surfaceReady = new CountDownLatch(1);
    private volatile Surface surface;

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        SurfaceView view = new SurfaceView(this);
        view.getHolder().setFixedSize(720, 864);
        view.getHolder().addCallback(this);
        setContentView(view);
    }

    @Override public void surfaceCreated(SurfaceHolder holder) {
        surface = holder.getSurface();
        surfaceReady.countDown();
    }
    @Override public void surfaceChanged(SurfaceHolder holder, int format, int width, int height) {
        surface = holder.getSurface();
    }
    @Override public void surfaceDestroyed(SurfaceHolder holder) { surface = null; }

    public Surface awaitSurface() throws InterruptedException {
        if (!surfaceReady.await(10, TimeUnit.SECONDS)) return null;
        Surface current = surface;
        return current != null && current.isValid() ? current : null;
    }
}
