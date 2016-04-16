package ethryoview.com.ethryoview;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.media.AudioFormat;
import android.media.AudioRecord;
import android.media.MediaRecorder;
import android.net.Uri;
import android.os.AsyncTask;
import android.os.Bundle;
import android.util.DisplayMetrics;
import android.util.Log;
import android.view.Menu;
import android.view.View;
import android.view.View.OnClickListener;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.TextView;
import android.widget.Toast;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.util.Arrays;

import au.com.bytecode.opencsv.CSVWriter;
import ca.uol.aig.fftpack.RealDoubleFFT;

public class MainActivity extends Activity {

    // taken from here, and adapted http://stackoverflow.com/questions/5511250/capturing-sound-for-analysis-and-visualizing-frequencies-in-android
    int frequency = 8000;
    int channelConfiguration = AudioFormat.CHANNEL_CONFIGURATION_MONO;
    int audioEncoding = AudioFormat.ENCODING_PCM_16BIT;
    private RealDoubleFFT transformer;
    int blockSize = 256;

    Button startStopButton;
    TextView frequencyTextView;
    boolean started = false;

    RecordAudio recordTask;

    ImageView imageView;
    ImageView imageView2;
    Bitmap bitmap;

    Canvas mainCanvas;
    Canvas transformedCanvas;
    Paint paint;

    int height;
    FileWriter  fileWriter;
    String filename = "data.csv";
    CSVWriter writer;

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        try {
            fileWriter = new FileWriter(getExternalCacheDir().getAbsolutePath() + File.pathSeparator + filename);
            writer = new CSVWriter(fileWriter);
        } catch (IOException e) {
            Log.e("ETHRYOVIEW", "Couldnt save file", e);
        }

        frequencyTextView = (TextView)(findViewById(R.id.frequencyTextView));

        startStopButton = (Button) this.findViewById(R.id.StartStopButton);
        startStopButton.setOnClickListener(new OnClickListener() {
            @Override
            public void onClick(View v) {
                if (started) {
                    started = false;
                    startStopButton.setText("Start");
                    recordTask.cancel(true);
                } else {
                    started = true;
                    startStopButton.setText("Stop");
                    recordTask = new RecordAudio();
                    recordTask.execute();
                }
            }
        });

        Button shareButton = (Button) this.findViewById(R.id.button);

        shareButton.setOnClickListener(new OnClickListener() {
            @Override
            public void onClick(View v) {
                showShareDialog();
            }
        });

        transformer = new RealDoubleFFT(blockSize);

        imageView = (ImageView) this.findViewById(R.id.ImageView01);

        DisplayMetrics dm = getResources().getDisplayMetrics();

        float heightFactor = 0.65f;

        int width = (int)((float)dm.widthPixels * 0.8);
        height = (int)((float)dm.heightPixels * heightFactor/2);

        bitmap = Bitmap.createBitmap(width, height,
                Bitmap.Config.ARGB_8888);

        transformedCanvas = new Canvas(bitmap);

        paint = new Paint();
        paint.setColor(Color.GREEN);

        imageView.setImageBitmap(bitmap);

        imageView2 = (ImageView) this.findViewById(R.id.ImageView02);
        Bitmap bitmap2 = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);

        mainCanvas = new Canvas(bitmap2);
        imageView2.setImageBitmap(bitmap2);

    }

    public class RecordAudio extends AsyncTask<Void, double[], Void> {

        @Override
        protected Void doInBackground(Void... arg0) {

            try {
                int bufferSize = AudioRecord.getMinBufferSize(frequency, channelConfiguration, audioEncoding);

                AudioRecord audioRecord = new AudioRecord(
                        MediaRecorder.AudioSource.MIC, frequency,
                        channelConfiguration, audioEncoding, bufferSize);

                short[] buffer = new short[blockSize];
                double[] toTransform = new double[blockSize];

                audioRecord.startRecording();

                // started = true; hopes this should true before calling
                // following while loop

                while (started) {
                    int bufferReadResult = audioRecord.read(buffer, 0, blockSize);

                    double peakAmplitude = Double.NEGATIVE_INFINITY;

                    for (int i = 0; i < blockSize && i < bufferReadResult; i++) {
                        double c = (double) buffer[i];
                        peakAmplitude = peakAmplitude > c ? peakAmplitude : c;
                        toTransform[i] = c/32768.0; // signed
                    }

                    // http://stackoverflow.com/questions/16072185/decibels-in-android
                    double peakAmplitudeInDecibel = 20 * Math.log10(peakAmplitude/32678.0);

                    double [] amplitudeArray = {peakAmplitudeInDecibel};

                    toTransform = hanningWindow(toTransform, 0, blockSize);
                    double [] mainTransform = Arrays.copyOf(toTransform, toTransform.length);

                    transformer.ft(toTransform);

                    publishProgress(mainTransform, toTransform, amplitudeArray);
                }

                audioRecord.stop();

            } catch (Throwable t) {
                Log.e("AudioRecord", "Recording Failed", t);
            }
            return null;
        }

        private double[] hanningWindow(double[] signal_in, int pos, int size)
        {
            for (int i = pos; i < pos + size; i++)
            {
                int j = i - pos; // j = index into Hann window function
                signal_in[i] = (signal_in[i] * 0.5 * (1.0 - Math.cos(2.0 * Math.PI * j / size)));
            }
            return signal_in;
        }

        @Override
        protected void onProgressUpdate(double[]... args) {

            double [] mainTransform = args[0];
            double [] toTransform = args[1];
            double peakAmplitude = args[2][0];

            mainCanvas.drawColor(Color.BLACK);
            transformedCanvas.drawColor(Color.BLACK);

            for (int x = 0; x < mainTransform.length; x++) {
               double y = mainTransform[x];
               int downy = (int) (height - (mainTransform[x] * height/10));
               int upy = height;
               mainCanvas.drawLine(x, downy, x, upy, paint);
            }

            int maxX = 0;
            Double max = Double.NEGATIVE_INFINITY;

            boolean lightUp = false;

            for (int x = 0; x < toTransform.length; x++) {

                double y = toTransform[x];

                if (y < 1500) {
                    break;
                }

                if (y > 400 && y < 900 && peakAmplitude > 30) {
                   lightUp = true;
                }

                maxX = max > y ? maxX : x;
                max = max > y ? max : y;
                int downy = (int) (height - (toTransform[x] * height/10));
                int upy = height;
                transformedCanvas.drawLine(x, downy, x, upy, paint);
            }

            maxX = maxX * frequency / (blockSize * 2);

            // frequencyTextView.setText(Double.toString(maxX) + " Hz");
            frequencyTextView.setText(Boolean.toString(lightUp));

            String unix = Long.toString(System.currentTimeMillis() / 100L);
            String [] toWrite = {unix, Double.toString(maxX)};
            writer.writeNext(toWrite);

            imageView.invalidate();
            imageView2.invalidate();
        }

    }

    private void showShareDialog() {
        try {
            writer.flush();
        } catch (IOException e) {
            Log.d("ETHRYOVIEW", "Error while trying to flush", e);
        }

        File f = new File(getExternalCacheDir().getAbsolutePath() + File.pathSeparator + filename);
        f.setReadable(true, false);

        if (f.exists()) {
            Intent intentShareFile = new Intent(Intent.ACTION_SEND);
            intentShareFile.setType("application/csv");
            intentShareFile.putExtra(Intent.EXTRA_STREAM, Uri.fromFile(f));
            intentShareFile.putExtra(Intent.EXTRA_SUBJECT, filename);
            intentShareFile.putExtra(Intent.EXTRA_TEXT, filename);
            startActivityForResult(Intent.createChooser(intentShareFile, "Share File"), 1);
        } else {
            Toast.makeText(getApplicationContext(), "No data exists", Toast.LENGTH_SHORT).show();
        }
    }

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        getMenuInflater().inflate(R.menu.menu_main, menu);
        return true;
    }
}
