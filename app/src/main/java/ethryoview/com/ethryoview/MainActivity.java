package ethryoview.com.ethryoview;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.drawable.BitmapDrawable;
import android.media.AudioFormat;
import android.media.AudioManager;
import android.media.AudioRecord;
import android.media.MediaRecorder;
import android.media.audiofx.AcousticEchoCanceler;
import android.media.audiofx.AutomaticGainControl;
import android.media.audiofx.NoiseSuppressor;
import android.net.Uri;
import android.os.AsyncTask;
import android.os.Build;
import android.os.Bundle;
import android.util.Log;
import android.view.Menu;
import android.view.View;
import android.view.View.OnClickListener;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.TextView;
import android.widget.Toast;

import com.github.mikephil.charting.charts.LineChart;
import com.github.mikephil.charting.components.YAxis;
import com.github.mikephil.charting.data.Entry;
import com.github.mikephil.charting.data.LineData;
import com.github.mikephil.charting.data.LineDataSet;
import com.github.mikephil.charting.interfaces.datasets.ILineDataSet;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;

import au.com.bytecode.opencsv.CSVWriter;
import ca.uol.aig.fftpack.RealDoubleFFT;

public class MainActivity extends Activity {

    // taken from here, and adapted http://stackoverflow.com/questions/5511250/capturing-sound-for-analysis-and-visualizing-frequencies-in-android
    int frequency = 11025;
    int channelConfiguration = AudioFormat.CHANNEL_CONFIGURATION_MONO;
    int audioEncoding = AudioFormat.ENCODING_PCM_16BIT;
    private RealDoubleFFT transformer;
    int blockSize = 256;
    long lastLightOn = 0;
    long difference = 0;
    long bpm = 0;

    Button startStopButton;
    TextView frequencyTextView;
    boolean started = false;

    RecordAudio recordTask;

    LineChart lineChart1;
    LineChart lineChart2;

    int height;
    FileWriter  fileWriter;
    String filename = "data.csv";
    CSVWriter writer;

    ImageView circleView;
    Paint paint;
    Canvas canvas;

    long lightLastStatus;
    boolean lightStatus;

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        long lightLastStatus = 0l;
        boolean lightStatus = false;

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

        lineChart1 = (LineChart) this.findViewById(R.id.ImageView01);
        lineChart2 = (LineChart) this.findViewById(R.id.ImageView02);
        circleView = (ImageView)findViewById(R.id.circleView);

        Bitmap bitmap = Bitmap.createBitmap(10, 10, Bitmap.Config.ARGB_8888);
        bitmap = bitmap.copy(bitmap.getConfig(), true);
        canvas = new Canvas(bitmap);

        paint = new Paint();                          //define paint and paint color
        paint.setColor(Color.RED);
        paint.setStyle(Paint.Style.FILL_AND_STROKE);
        paint.setStrokeWidth(2f);

        circleView.setImageBitmap(bitmap);
        canvas.drawCircle(5, 5, 1, paint);
        circleView.invalidate();
    }

    public class RecordAudio extends AsyncTask<Void, double[], Void> {

        @Override
        protected Void doInBackground(Void... arg0) {

            try {

                String TAG = "ETHRYOVIEW";

                int bufferSize = AudioRecord.getMinBufferSize(frequency, channelConfiguration, audioEncoding);
                AudioRecord audioRecord = new AudioRecord(MediaRecorder.AudioSource.MIC, frequency, channelConfiguration, audioEncoding, bufferSize);

                AudioManager audioManager = (AudioManager) getApplicationContext().getSystemService(Context.AUDIO_SERVICE);

                audioManager.setMode(AudioManager.MODE_IN_CALL);
                audioManager.setParameters("noise_suppression=on");

                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.JELLY_BEAN) {
                    Log.i(TAG, "Trying to clean up audio because running on SDK " + Build.VERSION.SDK_INT);

                    if (NoiseSuppressor.create(audioRecord.getAudioSessionId()) == null) {
                        Log.i(TAG, "NoiseSuppressor not present :(");
                    } else {
                        Log.i(TAG, "NoiseSuppressor enabled!");
                    }

                    if (AutomaticGainControl.create(audioRecord.getAudioSessionId()) == null) {
                        Log.i(TAG, "AutomaticGainControl not present :(");
                    } else {
                        Log.i(TAG, "AutomaticGainControl enabled!");
                    }

                    if (AcousticEchoCanceler.create(audioRecord.getAudioSessionId()) == null) {
                        Log.i(TAG, "AcousticEchoCanceler not present :(");
                    } else {
                        Log.i(TAG, "AcousticEchoCanceler enabled!");
                    }
                }

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
                    peakAmplitudeInDecibel += 56.9;
                    double [] amplitudeArray = { peakAmplitudeInDecibel };
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

            ArrayList<String> xVals = new ArrayList<String>();
            ArrayList<Entry> yVals = new ArrayList<Entry>();

            for (int x = 0; x < mainTransform.length; x++) {
                double y = mainTransform[x];
                int downy = (int) (height - (mainTransform[x] * height/10));
                int upy = height;
                xVals.add((x) + "");
                yVals.add(new Entry(new Float(y), x));
            }

            LineDataSet set1;

            set1 = new LineDataSet(yVals, "Time domain");
            ArrayList<ILineDataSet> dataSets = new ArrayList<ILineDataSet>();
            dataSets.add(set1); // add the datasets
            LineData data = new LineData(xVals, dataSets);
            lineChart1.setData(data);

            xVals = new ArrayList<String>();
            yVals = new ArrayList<Entry>();

            boolean lightUp = false;
            long time = 0;

            String lowfreq = ((EditText)(findViewById(R.id.editText2))).getText().toString();
            int lowFreq = 500;

            if (!lowfreq.isEmpty()) {
                lowFreq = Integer.parseInt(lowfreq);
            }

            String highfreq = ((EditText)(findViewById(R.id.editText3))).getText().toString();
            int highFreq = 700;

            if (!highfreq.isEmpty()) {
                highFreq = Integer.parseInt(highfreq);
            }

            String ampl = ((EditText)(findViewById(R.id.editText))).getText().toString();
            double amp = 38;

            if (!ampl.isEmpty()) {
                amp = Double.parseDouble(ampl);
            }


            int maxX = 0;
            Double max = Double.NEGATIVE_INFINITY;

            int maxFreq = 1000;

            String [] vals = new String [maxFreq+1];

            for(int i = 1; i < maxFreq + 1; i++) {
                vals[i] = "0";
            }

            for (int x = 0; x < toTransform.length; x++) {

                int freq = x * frequency/(blockSize * 2);

                if (freq > 1500) {
                    break;
                }
                if (freq > lowFreq && freq < highFreq && peakAmplitude > amp) {
                    lightUp = true;
                    time = System.currentTimeMillis();
                }
                double y = toTransform[x];

                maxX = max > y ? maxX : x;
                max = max > y ? max : y;

                xVals.add(freq + "");
                yVals.add(new Entry(new Float(y), x));

                if (freq > 0 && freq <= maxFreq && y > 0) {
                    vals[freq] = Double.toString(20 * Math.log10(y) + 56.9);
                }
            }

            if (lastLightOn != 0 && time != 0) {
                difference = time - lastLightOn;
            }

            if (difference > 400 && difference < 1500) {
               bpm = 60000/difference;
            }

            if (System.currentTimeMillis() - lastLightOn > 2000) {
                bpm = 0;
            }

            maxX = maxX * frequency / (blockSize * 2);

            if (bpm >= 40 && bpm <= 150) {

                if (!lightStatus) {
                    lightLastStatus = System.currentTimeMillis();
                }

                if (System.currentTimeMillis() - lightLastStatus < 2000) {
                    paint.setColor(Color.YELLOW);
                } else {
                    paint.setColor(Color.GREEN);
                }

                lightStatus = true;
            }
            else {
                lightStatus = false;
                lightLastStatus = 0;
                paint.setColor(Color.RED);
            }

            canvas.drawCircle(5, 5, 1, paint);

            circleView.invalidate();

            set1 = new LineDataSet(yVals, "Frequency domain");
            set1.setDrawValues(false);
            dataSets = new ArrayList<ILineDataSet>();
            dataSets.add(set1); // add the datasets
            data = new LineData(xVals, dataSets);

            lineChart2.getAxisLeft().setAxisMinValue(-10f);
            lineChart2.getAxisRight().setAxisMinValue(-10f);
            lineChart2.getAxisLeft().setAxisMaxValue(10f);
            lineChart2.getAxisRight().setAxisMaxValue(10f);
            lineChart2.setData(data);

            String toShow = Double.toString(peakAmplitude) + " dB | " + Double.toString(maxX) + " Hz" + " | " + Long.toString(bpm) + " BPM";

            frequencyTextView.setText(toShow);

            String unix = Long.toString(System.currentTimeMillis() / 100L);
            vals[0] = unix;
            writer.writeNext(vals);
            lineChart1.invalidate();
            lineChart2.invalidate();

            if (time != 0) {
                lastLightOn = time;
            }
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
