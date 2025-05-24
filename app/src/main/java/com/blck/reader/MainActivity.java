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

        String lastDoc = getLastOpenedDoc();
        if (isUriValid(lastDoc)) {
            // reopen last book, skip file picker
            startMuPDFActivity(Uri.parse(lastDoc));
        } else {
            pickerLauncher.launch(new String[]{
                    "application/epub+zip",
                    "application/pdf",
                    "text/html",
                    "application/octet-stream"});
        }
    }

    @Override
    protected void onRestart() {
        super.onRestart();
        saveLastOpenedDoc("none");
        pickerLauncher.launch(new String[]{
                "application/epub+zip",
                "application/pdf",
                "text/html",
                "application/octet-stream"});
    }

    private void initPicker() {
        pickerLauncher = registerForActivityResult(
                new ActivityResultContracts.OpenDocument(),
                result -> {
                    if (result != null) {
                        Log.i("MainActivity", "Selected document URI: " + result);
                        // persist access on restarts - scoped storage
                        getContentResolver().takePersistableUriPermission(
                                result, Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                        );
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
