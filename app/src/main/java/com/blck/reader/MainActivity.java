package com.blck.reader;

import android.content.ContentResolver;
import android.content.Intent;
import android.content.SharedPreferences;
import android.net.Uri;
import android.os.Bundle;
import android.util.Log;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AppCompatActivity;

import com.artifex.mupdf.viewer.DocumentActivity;

import java.io.InputStream;

public class MainActivity extends AppCompatActivity {

    private static boolean isInit = false;
    private ActivityResultLauncher<String[]> pickerLauncher;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        if (!isInit)
            isInit = true;
        else
            return;
        initPicker();
        Log.i("Oncreate", "");

        String lastDoc = getLastOpenedDoc();
        if (isUriValid(lastDoc)) {
            startMuPDFActivity(Uri.parse(lastDoc));
        } else {
            pickerLauncher.launch(new String[]{"*/*"});
        }
    }

    @Override
    protected void onRestart() {
        super.onRestart();
        Log.i("onrestart", "");
        saveLastOpenedDoc("none");
        pickerLauncher.launch(new String[]{"*/*"});
    }

    private void initPicker() {
        pickerLauncher = registerForActivityResult(
                new ActivityResultContracts.OpenDocument(),
                result -> {
                    if (result != null) {
                        Log.i("MainActivity", "Selected document URI: " + result);
                        saveLastOpenedDoc(result.toString());
                        startMuPDFActivity(result);
                    }
                }
        );
    }

    public void startMuPDFActivity(Uri documentUri) {
        Intent intent = new Intent(this, DocumentActivity.class);
        intent.setAction(Intent.ACTION_VIEW);
        intent.setData(documentUri);
        startActivity(intent);
    }

    public void saveLastOpenedDoc(String docName) {
        if (docName == null)
            return;
        Log.i("savingdoc", docName);
        SharedPreferences sharedPreferences = getSharedPreferences("lastDoc", MODE_PRIVATE);
        SharedPreferences.Editor editor = sharedPreferences.edit();
        editor.putString("lastDoc", docName);
        editor.apply();
    }

    public String getLastOpenedDoc() {
        SharedPreferences sharedPreferences = getSharedPreferences("lastDoc", MODE_PRIVATE);
        return sharedPreferences.getString("lastDoc", null);
    }

    public boolean isUriValid(String uri) {
        if (uri == null)
            return false;
        // doesnt exist, IOexception, permission exception = invalid
        ContentResolver cr = getContentResolver();
        try (InputStream ignored = cr.openInputStream(Uri.parse(uri))) {
            return true;
        } catch (Exception e) {
            return false;
        }
    }
}
