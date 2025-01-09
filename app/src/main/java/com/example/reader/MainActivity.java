package com.example.reader;

import android.content.ContentResolver;
import android.content.Intent;
import android.content.SharedPreferences;
import android.net.Uri;
import android.os.Bundle;
import android.util.Log;

import androidx.appcompat.app.AppCompatActivity;

import com.artifex.mupdf.viewer.DocumentActivity;

import java.io.InputStream;

public class MainActivity extends AppCompatActivity {

    private static final int PICK_DOCUMENT_REQUEST_CODE = 1001; // Unique request code

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        String lastDoc = getLastOpenedDoc();
        if (isUriValid(lastDoc)) {
            startMuPDFActivity(Uri.parse(lastDoc));
        } else {
            openFilePicker();
        }
    }

    private void openFilePicker() {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("*/*");  // You can specify specific mime types (e.g., "application/pdf")
        startActivityForResult(intent, PICK_DOCUMENT_REQUEST_CODE);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);

        if (resultCode == RESULT_OK && requestCode == PICK_DOCUMENT_REQUEST_CODE) {
            // The user selected a file
            if (data != null) {
                Uri documentUri = data.getData();
                Log.i("MainActivity", "Selected document URI: " + documentUri.toString());
                startMuPDFActivity(documentUri);
            }
        }
    }

    public void startMuPDFActivity(Uri documentUri) {
        saveLastOpenedDoc(documentUri.toString());
        Intent intent = new Intent(this, DocumentActivity.class);
        intent.setAction(Intent.ACTION_VIEW);
        intent.setData(documentUri);
        startActivity(intent);
        finish();
    }

    public void saveLastOpenedDoc(String docName) {
        SharedPreferences sharedPreferences = getSharedPreferences("lastDoc", MODE_PRIVATE);
        SharedPreferences.Editor editor = sharedPreferences.edit();
        editor.putString("lastDoc", docName);
        editor.apply();
    }

    public String getLastOpenedDoc() {
        SharedPreferences sharedPreferences = getSharedPreferences("lastDoc", MODE_PRIVATE);
        return sharedPreferences.getString("lastDoc", "none");
    }

    public boolean isUriValid(String uri) {
        Log.i("uri check", uri);
        if (uri.equals("none"))
            return false;

        ContentResolver cr = getContentResolver();
        try (InputStream is = cr.openInputStream(Uri.parse(uri))) {
            return true;
        } catch (Exception e) {
            return false;
        }
    }
}
