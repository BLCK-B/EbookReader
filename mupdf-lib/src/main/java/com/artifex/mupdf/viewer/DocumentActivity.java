package com.artifex.mupdf.viewer;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.res.Resources;
import android.database.Cursor;
import android.graphics.Color;
import android.net.Uri;
import android.os.Bundle;
import android.provider.OpenableColumns;
import android.text.Editable;
import android.text.TextWatcher;
import android.util.DisplayMetrics;
import android.util.Log;
import android.view.KeyEvent;
import android.view.Menu;
import android.view.View;
import android.view.Window;
import android.view.WindowManager;
import android.view.animation.Animation;
import android.view.animation.TranslateAnimation;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodManager;
import android.widget.EditText;
import android.widget.ImageButton;
import android.widget.PopupMenu;
import android.widget.RelativeLayout;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.ViewAnimator;

import com.artifex.mupdf.fitz.SeekableInputStream;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Locale;

public class DocumentActivity extends Activity {
    private final String APP = "MuPDF";

    /* The core rendering instance */
    enum TopBarMode {Main, Search}

    private final int OUTLINE_REQUEST = 0;
    private MuPDFCore core;
    private String mDocTitle;
    private String mDocKey;
    private ReaderView mDocView;
    private View mButtonsView;
    private boolean mButtonsVisible;
    private TextView mDocNameView;
    private SeekBar mPageSlider;
    private int mPageSliderRes;
    private TextView mPageNumberView;
    private ImageButton mSearchButton;
    private ImageButton mOutlineButton;
    private ViewAnimator mTopBarSwitcher;
    private ImageButton mLinkButton;
    private TopBarMode mTopBarMode = TopBarMode.Main;
    private ImageButton mSearchBack;
    private ImageButton mSearchFwd;
    private ImageButton mSearchClose;
    private EditText mSearchText;
    private SearchTask mSearchTask;
    private AlertDialog.Builder mAlertBuilder;
    private boolean mLinkHighlight = false;
    private ArrayList<OutlineActivity.Item> mFlatOutline;
    private boolean mReturnToLibraryActivity = false;

    protected int mDisplayDPI;
    private int mLayoutEM = 10;
    private int mLayoutW = 312;
    private int mLayoutH = 504;

    protected View mLayoutButton;
    protected PopupMenu mLayoutPopupMenu;

    private int displayedPage = 0;

    private MuPDFCore openBuffer(byte[] buffer, String magic) {
        try {
            core = new MuPDFCore(buffer, magic);
        } catch (Exception e) {
            Log.e(APP, "Error opening document buffer: " + e);
            return null;
        }
        return core;
    }

    private MuPDFCore openStream(SeekableInputStream stm, String magic) {
        try {
            core = new MuPDFCore(stm, magic);
        } catch (Exception e) {
            Log.e(APP, "Error opening document stream: " + e);
            return null;
        }
        return core;
    }

    private MuPDFCore openCore(Uri uri, long size, String mimetype) throws IOException {
        ContentResolver cr = getContentResolver();
        Log.i(APP, "Opening document " + uri);
        InputStream is = cr.openInputStream(uri);
        byte[] buf = null;
        int used = -1;
        try {
            final int limit = 8 * 1024 * 1024;
            if (size < 0) { // size is unknown
                buf = new byte[limit];
                used = is.read(buf);
                boolean atEOF = is.read() == -1;
                if (used < 0 || (used == limit && !atEOF)) // no or partial data
                    buf = null;
            } else if (size <= limit) { // size is known and below limit
                buf = new byte[(int) size];
                used = is.read(buf);
                if (used < 0 || used < size) // no or partial data
                    buf = null;
            }
            if (buf != null && buf.length != used) {
                byte[] newbuf = new byte[used];
                System.arraycopy(buf, 0, newbuf, 0, used);
                buf = newbuf;
            }
        } catch (OutOfMemoryError e) {
            buf = null;
        } finally {
            is.close();
        }
        if (buf != null) {
            Log.i(APP, "  Opening document from memory buffer of size " + buf.length);
            return openBuffer(buf, mimetype);
        } else {
            Log.i(APP, "  Opening document from stream");
            return openStream(new ContentInputStream(cr, uri, size), mimetype);
        }
    }

    private void showCannotOpenDialog(String reason) {
        Resources res = getResources();
        AlertDialog alert = mAlertBuilder.create();
        setTitle(String.format(Locale.ROOT, res.getString(R.string.cannot_open_document_Reason), reason));
        alert.setButton(AlertDialog.BUTTON_POSITIVE, getString(R.string.dismiss),
                (dialog, which) -> finish());
        alert.show();
    }

    /**
     * Called when the activity is first created.
     */
    @Override
    public void onCreate(final Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        requestWindowFeature(Window.FEATURE_NO_TITLE);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN);

        DisplayMetrics metrics = new DisplayMetrics();
        getWindowManager().getDefaultDisplay().getMetrics(metrics);
        mDisplayDPI = metrics.densityDpi;

        mAlertBuilder = new AlertDialog.Builder(this);

        if (core == null) {
            if (savedInstanceState != null && savedInstanceState.containsKey("DocTitle")) {
                mDocTitle = savedInstanceState.getString("DocTitle");
            }
            Intent intent = getIntent();

            mReturnToLibraryActivity = intent.getIntExtra(getComponentName().getPackageName() + ".ReturnToLibraryActivity", 0) != 0;

            if (Intent.ACTION_VIEW.equals(intent.getAction())) {
                Uri uri = intent.getData();
                String mimetype = getIntent().getType();

                if (uri == null) {
                    showCannotOpenDialog("No document uri to open");
                    return;
                }

                mDocKey = uri.toString();

                Log.i(APP, "OPEN URI " + uri);
                Log.i(APP, "  MAGIC (Intent) " + mimetype);

                mDocTitle = null;
                long size = -1;

                try (Cursor cursor = getContentResolver().query(uri, null, null, null, null)) {
                    if (cursor != null && cursor.moveToFirst()) {
                        int idx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME);
                        if (idx >= 0 && cursor.getType(idx) == Cursor.FIELD_TYPE_STRING)
                            mDocTitle = cursor.getString(idx);

                        idx = cursor.getColumnIndex(OpenableColumns.SIZE);
                        if (idx >= 0 && cursor.getType(idx) == Cursor.FIELD_TYPE_INTEGER)
                            size = cursor.getLong(idx);

                        if (size == 0)
                            size = -1;
                    }
                } catch (Exception x) {
                    // Ignore any exception and depend on default values for title and size (unless one was decoded)
                }

                Log.i(APP, "  NAME " + mDocTitle);
                Log.i(APP, "  SIZE " + size);

                if (mimetype == null || mimetype.equals("application/octet-stream")) {
                    mimetype = getContentResolver().getType(uri);
                    Log.i(APP, "  MAGIC (Resolved) " + mimetype);
                }
                if (mimetype == null || mimetype.equals("application/octet-stream")) {
                    mimetype = mDocTitle;
                    Log.i(APP, "  MAGIC (Filename) " + mimetype);
                }

                try {
                    core = openCore(uri, size, mimetype);
                    SearchTaskResult.set(null);
                } catch (Exception x) {
                    showCannotOpenDialog(x.toString());
                    return;
                }
            }
            if (core != null && core.countPages() == 0) {
                core = null;
            }
        }
        if (core == null) {
            AlertDialog alert = mAlertBuilder.create();
            alert.setTitle(R.string.cannot_open_document);
            alert.setButton(AlertDialog.BUTTON_POSITIVE, getString(R.string.dismiss),
                    (dialog, which) -> finish());
            alert.setOnCancelListener(dialog -> finish());
            alert.show();
            return;
        }
        createUI(savedInstanceState);
        // my additions
        applySavedData();
        updateLayoutInit();
    }

    public void persistData(int fontSize, int currentPage) {
        Log.i(APP, "Saving persistence " + fontSize + " " + currentPage);
        SharedPreferences sharedPreferences = getSharedPreferences("LastState", MODE_PRIVATE);
        SharedPreferences.Editor editor = sharedPreferences.edit();
        editor.putInt("fontSize", fontSize);
        editor.putInt("currentPage", currentPage);
        editor.apply();
    }

    public void applySavedData() {
        SharedPreferences sharedPreferences = getSharedPreferences("LastState", MODE_PRIVATE);
        int fontSize = sharedPreferences.getInt("fontSize", 9);
        int pageNum = sharedPreferences.getInt("currentPage", 0);
        Log.i(APP, "Loading persistence " + fontSize + " " + pageNum);
        setFontSize(fontSize);
        setDisplayedPage(pageNum);
    }

    public void setFontSize(int fontSize) {
        mLayoutEM = fontSize;
    }

    public void setDisplayedPage(int page) {
        displayedPage = page;
    }

    public void updateLayoutInit() {
        core.updateLayout(mLayoutW, mLayoutH, mLayoutEM);
        mDocView.setDisplayedViewIndex(displayedPage);
    }

    public void updateLayoutFontChange() {
        displayedPage = mDocView.getDisplayedViewIndex();
        final float oldProgress = (float) displayedPage / core.countPages();

        core.updateLayout(mLayoutW, mLayoutH, mLayoutEM);

        mFlatOutline = null;
        mDocView.mHistory.clear();
        mDocView.refresh();

        // update page count indicator
        updatePageNumView(displayedPage);
        // update slider
        mPageSlider.setMax((core.countPages() - 1) * mPageSliderRes);

        final int pageAdjustedForProgress = Math.round(oldProgress * core.countPages());
        mDocView.setDisplayedViewIndex(pageAdjustedForProgress);
    }

    public void createUI(Bundle savedInstanceState) {
        if (core == null)
            return;

        // Now create the UI.
        // First create the document view
        mDocView = new ReaderView(this) {
            @Override
            protected void onMoveToChild(int i) {
                if (core == null)
                    return;

                mPageNumberView.setText(String.format(Locale.ROOT, "%d / %d", i + 1, core.countPages()));
                mPageSlider.setMax((core.countPages() - 1) * mPageSliderRes);
                mPageSlider.setProgress(i * mPageSliderRes);
                super.onMoveToChild(i);
            }

            @Override
            protected void onTapMainDocArea() {
                if (!mButtonsVisible) {
                    showButtons();
                } else {
                    if (mTopBarMode == TopBarMode.Main)
                        hideButtons();
                }
            }

            @Override
            protected void onDocMotion() {
                hideButtons();
            }
        };
        mDocView.setAdapter(new PageAdapter(this, core));

        mSearchTask = new SearchTask(this, core) {
            @Override
            protected void onTextFound(SearchTaskResult result) {
                SearchTaskResult.set(result);
                // Ask the ReaderView to move to the resulting page
                mDocView.setDisplayedViewIndex(result.pageNumber);
                // Make the ReaderView act on the change to SearchTaskResult via overridden onChildSetup method.
                mDocView.resetupChildren();
            }
        };

        // Make the buttons overlay, and store all its controls in variables
        makeButtonsView();

        // Set up the page slider
        int smax = Math.max(core.countPages() - 1, 1);
        mPageSliderRes = ((10 + smax - 1) / smax) * 2;

        // Set the file-name text
        String docTitle = core.getTitle();
        if (docTitle != null)
            mDocNameView.setText(docTitle);
        else
            mDocNameView.setText(mDocTitle);

        // Activate the seekbar
        mPageSlider.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            public void onStopTrackingTouch(SeekBar seekBar) {
                mDocView.pushHistory();
                mDocView.setDisplayedViewIndex((seekBar.getProgress() + mPageSliderRes / 2) / mPageSliderRes);
            }

            public void onStartTrackingTouch(SeekBar seekBar) {
            }

            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                updatePageNumView((progress + mPageSliderRes / 2) / mPageSliderRes);
            }
        });

        // Activate the search-preparing button
        mSearchButton.setOnClickListener(v -> searchModeOn());
        mSearchClose.setOnClickListener(v -> searchModeOff());

        // Search invoking buttons are disabled while there is no text specified
        mSearchBack.setEnabled(false);
        mSearchFwd.setEnabled(false);
        mSearchBack.setColorFilter(Color.argb(255, 128, 128, 128));
        mSearchFwd.setColorFilter(Color.argb(255, 128, 128, 128));

        // React to interaction with the text widget
        mSearchText.addTextChangedListener(new TextWatcher() {

            public void afterTextChanged(Editable s) {
                boolean haveText = !s.toString().isEmpty();
                setButtonEnabled(mSearchBack, haveText);
                setButtonEnabled(mSearchFwd, haveText);

                // Remove any previous search results
                if (SearchTaskResult.get() != null && !mSearchText.getText().toString().equals(SearchTaskResult.get().txt)) {
                    SearchTaskResult.set(null);
                    mDocView.resetupChildren();
                }
            }

            public void beforeTextChanged(CharSequence s, int start, int count, int after) {
            }

            public void onTextChanged(CharSequence s, int start, int before, int count) {
            }
        });

        //React to Done button on keyboard
        mSearchText.setOnEditorActionListener((v, actionId, event) -> {
            if (actionId == EditorInfo.IME_ACTION_DONE)
                search(1);
            return false;
        });

        mSearchText.setOnKeyListener((v, keyCode, event) -> {
            if (event.getAction() == KeyEvent.ACTION_DOWN && keyCode == KeyEvent.KEYCODE_ENTER)
                search(1);
            return false;
        });

        // Activate search invoking buttons
        mSearchBack.setOnClickListener(v -> search(-1));
        mSearchFwd.setOnClickListener(v -> search(1));

        mLinkButton.setOnClickListener(v -> setLinkHighlight(!mLinkHighlight));

        if (core.isReflowable()) {
            mLayoutButton.setVisibility(View.VISIBLE);
            mLayoutPopupMenu = new PopupMenu(this, mLayoutButton);
            mLayoutPopupMenu.getMenuInflater().inflate(R.menu.layout_menu, mLayoutPopupMenu.getMenu());
            mLayoutPopupMenu.setOnMenuItemClickListener(item -> {
                int id = item.getItemId();
                if (id == R.id.action_layout_8pt) mLayoutEM = 8;
                else if (id == R.id.action_layout_9pt) mLayoutEM = 9;
                else if (id == R.id.action_layout_10pt) mLayoutEM = 10;
                else if (id == R.id.action_layout_11pt) mLayoutEM = 11;
                else if (id == R.id.action_layout_12pt) mLayoutEM = 12;
                else if (id == R.id.action_layout_13pt) mLayoutEM = 13;
                else if (id == R.id.action_layout_14pt) mLayoutEM = 14;
                updateLayoutFontChange();
                return true;
            });
            mLayoutButton.setOnClickListener(v -> mLayoutPopupMenu.show());
        }

        if (core.hasOutline()) {
            mOutlineButton.setOnClickListener(v -> {
                if (mFlatOutline == null)
                    mFlatOutline = core.getOutline();
                if (mFlatOutline != null) {
                    Intent intent = new Intent(DocumentActivity.this, OutlineActivity.class);
                    Bundle bundle = new Bundle();
                    bundle.putInt("POSITION", mDocView.getDisplayedViewIndex());
                    bundle.putSerializable("OUTLINE", mFlatOutline);
                    intent.putExtra("PALLETBUNDLE", Pallet.sendBundle(bundle));
                    startActivityForResult(intent, OUTLINE_REQUEST);
                }
            });
        } else {
            mOutlineButton.setVisibility(View.GONE);
        }
        if (savedInstanceState == null || !savedInstanceState.getBoolean("ButtonsHidden", false))
            showButtons();
        if (savedInstanceState != null && savedInstanceState.getBoolean("SearchMode", false))
            searchModeOn();
        // Stick the document view and the buttons overlay into a parent view
        RelativeLayout layout = new RelativeLayout(this);
        layout.setBackgroundColor(Color.DKGRAY);
        layout.addView(mDocView);
        layout.addView(mButtonsView);
        setContentView(layout);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        if (requestCode == OUTLINE_REQUEST) {
            if (resultCode >= RESULT_FIRST_USER && mDocView != null) {
                mDocView.pushHistory();
                mDocView.setDisplayedViewIndex(resultCode - RESULT_FIRST_USER);
            }
        }
        super.onActivityResult(requestCode, resultCode, data);
    }

    @Override
    protected void onSaveInstanceState(Bundle outState) {
        super.onSaveInstanceState(outState);

        if (mDocKey != null && mDocView != null) {
            if (mDocTitle != null)
                outState.putString("DocTitle", mDocTitle);
        }

        if (!mButtonsVisible)
            outState.putBoolean("ButtonsHidden", true);

        if (mTopBarMode == TopBarMode.Search)
            outState.putBoolean("SearchMode", true);
    }

    @Override
    protected void onPause() {
        super.onPause();
    }

    public void onDestroy() {
        if (mDocView != null) {
            mDocView.applyToChildren(new ReaderView.ViewMapper() {
                void applyToView(View view) {
                    ((PageView) view).releaseBitmaps();
                }
            });
        }
        if (core != null)
            core.onDestroy();
        core = null;
        super.onDestroy();
    }

    private void setButtonEnabled(ImageButton button, boolean enabled) {
        button.setEnabled(enabled);
        button.setColorFilter(enabled ? Color.argb(255, 255, 255, 255) : Color.argb(255, 128, 128, 128));
    }

    private void setLinkHighlight(boolean highlight) {
        mLinkHighlight = highlight;
        // LINK_COLOR tint
        mLinkButton.setColorFilter(highlight ? Color.argb(0xFF, 0x00, 0x66, 0xCC) : Color.argb(0xFF, 255, 255, 255));
        // Inform pages of the change.
        mDocView.setLinksEnabled(highlight);
    }

    private void showButtons() {
        if (core == null)
            return;
        if (!mButtonsVisible) {
            mButtonsVisible = true;
            // Update page number text and slider
            int index = mDocView.getDisplayedViewIndex();
            updatePageNumView(index);
            mPageSlider.setMax((core.countPages() - 1) * mPageSliderRes);
            mPageSlider.setProgress(index * mPageSliderRes);
            if (mTopBarMode == TopBarMode.Search) {
                mSearchText.requestFocus();
                showKeyboard();
            }
            Animation anim = new TranslateAnimation(0, 0, -mTopBarSwitcher.getHeight(), 0);
            anim.setDuration(50);
            anim.setAnimationListener(new Animation.AnimationListener() {
                public void onAnimationStart(Animation animation) {
                    mTopBarSwitcher.setVisibility(View.VISIBLE);
                }

                public void onAnimationRepeat(Animation animation) {
                }

                public void onAnimationEnd(Animation animation) {
                }
            });
            mTopBarSwitcher.startAnimation(anim);

            anim = new TranslateAnimation(0, 0, mPageSlider.getHeight(), 0);
            anim.setDuration(50);
            anim.setAnimationListener(new Animation.AnimationListener() {
                public void onAnimationStart(Animation animation) {
                    mPageSlider.setVisibility(View.VISIBLE);
                }

                public void onAnimationRepeat(Animation animation) {
                }

                public void onAnimationEnd(Animation animation) {
                    mPageNumberView.setVisibility(View.VISIBLE);
                }
            });
            mPageSlider.startAnimation(anim);
        }
    }

    private void hideButtons() {
        if (mButtonsVisible) {
            mButtonsVisible = false;
            hideKeyboard();

            Animation anim = new TranslateAnimation(0, 0, 0, -mTopBarSwitcher.getHeight());
            anim.setDuration(50);
            anim.setAnimationListener(new Animation.AnimationListener() {
                public void onAnimationStart(Animation animation) {
                }

                public void onAnimationRepeat(Animation animation) {
                }

                public void onAnimationEnd(Animation animation) {
                    mTopBarSwitcher.setVisibility(View.INVISIBLE);
                }
            });
            mTopBarSwitcher.startAnimation(anim);

            anim = new TranslateAnimation(0, 0, 0, mPageSlider.getHeight());
            anim.setDuration(50);
            anim.setAnimationListener(new Animation.AnimationListener() {
                public void onAnimationStart(Animation animation) {
                    mPageNumberView.setVisibility(View.INVISIBLE);
                }

                public void onAnimationRepeat(Animation animation) {
                }

                public void onAnimationEnd(Animation animation) {
                    mPageSlider.setVisibility(View.INVISIBLE);
                }
            });
            mPageSlider.startAnimation(anim);
        }
    }

    private void searchModeOn() {
        if (mTopBarMode != TopBarMode.Search) {
            mTopBarMode = TopBarMode.Search;
            //Focus on EditTextWidget
            mSearchText.requestFocus();
            showKeyboard();
            mTopBarSwitcher.setDisplayedChild(mTopBarMode.ordinal());
        }
    }

    private void searchModeOff() {
        if (mTopBarMode == TopBarMode.Search) {
            mTopBarMode = TopBarMode.Main;
            hideKeyboard();
            mTopBarSwitcher.setDisplayedChild(mTopBarMode.ordinal());
            SearchTaskResult.set(null);
            // Make the ReaderView act on the change to mSearchTaskResult
            // via overridden onChildSetup method.
            mDocView.resetupChildren();
        }
    }

    private void updatePageNumView(int index) {
        if (core == null)
            return;
        mPageNumberView.setText(String.format(Locale.ROOT, "%d / %d", index + 1, core.countPages()));
    }

    private void makeButtonsView() {
        mButtonsView = getLayoutInflater().inflate(R.layout.document_activity, null);
        mDocNameView = mButtonsView.findViewById(R.id.docNameText);
        mPageSlider = mButtonsView.findViewById(R.id.pageSlider);
        mPageNumberView = mButtonsView.findViewById(R.id.pageNumber);
        mSearchButton = mButtonsView.findViewById(R.id.searchButton);
        mOutlineButton = mButtonsView.findViewById(R.id.outlineButton);
        mTopBarSwitcher = mButtonsView.findViewById(R.id.switcher);
        mSearchBack = mButtonsView.findViewById(R.id.searchBack);
        mSearchFwd = mButtonsView.findViewById(R.id.searchForward);
        mSearchClose = mButtonsView.findViewById(R.id.searchClose);
        mSearchText = mButtonsView.findViewById(R.id.searchText);
        mLinkButton = mButtonsView.findViewById(R.id.linkButton);
        mLayoutButton = mButtonsView.findViewById(R.id.layoutButton);
        mTopBarSwitcher.setVisibility(View.INVISIBLE);
        mPageNumberView.setVisibility(View.INVISIBLE);
        mPageSlider.setVisibility(View.INVISIBLE);
    }

    private void showKeyboard() {
        InputMethodManager imm = (InputMethodManager) getSystemService(Context.INPUT_METHOD_SERVICE);
        if (imm != null)
            imm.showSoftInput(mSearchText, 0);
    }

    private void hideKeyboard() {
        InputMethodManager imm = (InputMethodManager) getSystemService(Context.INPUT_METHOD_SERVICE);
        if (imm != null)
            imm.hideSoftInputFromWindow(mSearchText.getWindowToken(), 0);
    }

    private void search(int direction) {
        hideKeyboard();
        int displayPage = mDocView.getDisplayedViewIndex();
        SearchTaskResult r = SearchTaskResult.get();
        int searchPage = r != null ? r.pageNumber : -1;
        mSearchTask.search(mSearchText.getText().toString(), direction, displayPage, searchPage);
    }

    @Override
    public boolean onSearchRequested() {
        if (mButtonsVisible && mTopBarMode == TopBarMode.Search) {
            hideButtons();
        } else {
            showButtons();
            searchModeOn();
        }
        return super.onSearchRequested();
    }

    @Override
    public boolean onPrepareOptionsMenu(Menu menu) {
        if (mButtonsVisible && mTopBarMode != TopBarMode.Search) {
            hideButtons();
        } else {
            showButtons();
            searchModeOff();
        }
        return super.onPrepareOptionsMenu(menu);
    }

    @Override
    protected void onStart() {
        super.onStart();
    }

    @Override
    protected void onStop() {
        persistData(mLayoutEM, mDocView.getDisplayedViewIndex());
        super.onStop();
    }

    @Override
    public void onBackPressed() {
        if (mDocView == null || (mDocView != null && !mDocView.popHistory())) {
            super.onBackPressed();
            if (mReturnToLibraryActivity) {
                Intent intent = getPackageManager().getLaunchIntentForPackage(getComponentName().getPackageName());
                startActivity(intent);
            }
        }
    }
}
