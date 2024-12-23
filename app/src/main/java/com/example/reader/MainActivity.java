package com.example.reader;

import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.provider.DocumentsContract;
import android.util.Log;
import androidx.appcompat.app.AppCompatActivity;

import com.artifex.mupdf.viewer.DocumentActivity;

public class MainActivity extends AppCompatActivity {

    private static final int PICK_DOCUMENT_REQUEST_CODE = 1001; // Unique request code

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        // Trigger file picker on activity start
        openFilePicker();
    }

    // Open the file picker for the user to select a document
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

                // Start MuPDF activity with the selected document URI
                startMuPDFActivity(documentUri);
            }
        }
    }

    // Start MuPDF activity with the selected document URI
    public void startMuPDFActivity(Uri documentUri) {
        Intent intent = new Intent(this, DocumentActivity.class);
        intent.setAction(Intent.ACTION_VIEW);
        intent.setData(documentUri);
        startActivity(intent);

        // Finish MainActivity to prevent it from being displayed
        finish();
    }
}
