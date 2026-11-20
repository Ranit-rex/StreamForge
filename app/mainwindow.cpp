#include "mainwindow.h"
#include <QFrame>
#include <QHBoxLayout>
#include <QLabel>
#include <QListWidget>
#include <QPushButton>
#include <QSizePolicy>
#include <QVBoxLayout>
#include <QWidget>

namespace {
QLabel *makeSectionLabel(const QString &text) {
    auto *label = new QLabel(text);
    label->setObjectName("sectionLabel");
    return label;
}
QPushButton *makeToolButton(const QString &text) {
    auto *button = new QPushButton(text);
    button->setCursor(Qt::PointingHandCursor);
    button->setMinimumHeight(36);
    return button;
}
}

MainWindow::MainWindow(QWidget *parent) : QMainWindow(parent) {
    setWindowTitle("StreamForge");
    resize(1440, 900);
    setMinimumSize(1050, 680);
    buildInterface();
    applyTheme();

    const bool engineReady = engine_.initialize();
    engineStatusLabel_->setText(engineReady
        ? "●  LIBOBS ENGINE INITIALIZED"
        : "●  LIBOBS SDK NOT AVAILABLE");
    engineStatusLabel_->setToolTip(engine_.statusMessage());
}

QWidget *MainWindow::createPanel(const QString &title, QWidget *content, QWidget *parent) {
    auto *panel = new QFrame(parent);
    panel->setObjectName("panel");
    auto *layout = new QVBoxLayout(panel);
    layout->setContentsMargins(14, 12, 14, 14);
    layout->setSpacing(10);
    layout->addWidget(makeSectionLabel(title));
    layout->addWidget(content, 1);
    return panel;
}

void MainWindow::buildInterface() {
    auto *central = new QWidget(this);
    auto *root = new QVBoxLayout(central);
    root->setContentsMargins(16, 14, 16, 14);
    root->setSpacing(12);

    auto *topBar = new QHBoxLayout();
    auto *brand = new QLabel("STREAMFORGE");
    brand->setObjectName("brand");
    auto *workspace = new QLabel("LIVE PRODUCTION");
    workspace->setObjectName("eyebrow");
    topBar->addWidget(brand);
    topBar->addSpacing(18);
    topBar->addWidget(workspace);
    topBar->addStretch();
    engineStatusLabel_ = new QLabel("●  CHECKING ENGINE");
    engineStatusLabel_->setObjectName("statusPill");
    topBar->addWidget(engineStatusLabel_);
    root->addLayout(topBar);

    auto *workspaceRow = new QHBoxLayout();
    workspaceRow->setSpacing(12);
    sceneList_ = new QListWidget();
    sceneList_->addItems({"Starting Soon", "Gameplay", "Just Chatting", "BRB", "Ending"});
    sceneList_->setCurrentRow(1);
    sceneList_->setMinimumWidth(175);
    workspaceRow->addWidget(createPanel("SCENES", sceneList_), 2);

    auto *previewContent = new QWidget();
    auto *previewLayout = new QVBoxLayout(previewContent);
    previewLayout->setContentsMargins(0, 0, 0, 0);
    auto *preview = new QFrame();
    preview->setObjectName("previewCanvas");
    preview->setMinimumSize(420, 270);
    auto *previewInner = new QVBoxLayout(preview);
    auto *previewIcon = new QLabel("▣");
    previewIcon->setObjectName("previewIcon");
    previewIcon->setAlignment(Qt::AlignCenter);
    auto *previewTitle = new QLabel("Your stream preview");
    previewTitle->setObjectName("previewTitle");
    previewTitle->setAlignment(Qt::AlignCenter);
    auto *previewHint = new QLabel("Capture sources and the live composition preview are coming with the next engine milestone.");
    previewHint->setObjectName("mutedText");
    previewHint->setWordWrap(true);
    previewHint->setAlignment(Qt::AlignCenter);
    previewInner->addStretch();
    previewInner->addWidget(previewIcon);
    previewInner->addWidget(previewTitle);
    previewInner->addWidget(previewHint);
    previewInner->addStretch();
    previewLayout->addWidget(preview, 1);
    previewStatusLabel_ = new QLabel("No media capture is active.");
    previewStatusLabel_->setObjectName("mutedText");
    previewLayout->addWidget(previewStatusLabel_);
    workspaceRow->addWidget(createPanel("PREVIEW", previewContent), 6);

    sourceList_ = new QListWidget();
    sourceList_->addItems({"Display Capture", "Game Capture", "Webcam", "Microphone"});
    sourceList_->setMinimumWidth(190);
    workspaceRow->addWidget(createPanel("SOURCES", sourceList_), 2);
    root->addLayout(workspaceRow, 7);

    auto *bottomRow = new QHBoxLayout();
    bottomRow->setSpacing(12);
    auto *audioContent = new QWidget();
    auto *audioLayout = new QVBoxLayout(audioContent);
    audioLayout->setContentsMargins(0, 0, 0, 0);
    for (const QString &channel : {"Desktop Audio", "Microphone"}) {
        auto *line = new QHBoxLayout();
        auto *name = new QLabel(channel);
        name->setMinimumWidth(115);
        auto *meter = new QFrame();
        meter->setObjectName("meterTrack");
        meter->setMinimumHeight(9);
        meter->setSizePolicy(QSizePolicy::Expanding, QSizePolicy::Fixed);
        line->addWidget(name);
        line->addWidget(meter, 1);
        line->addWidget(new QLabel("— dB"));
        audioLayout->addLayout(line);
    }
    bottomRow->addWidget(createPanel("AUDIO MIXER", audioContent), 4);

    auto *outputContent = new QWidget();
    auto *outputLayout = new QVBoxLayout(outputContent);
    outputLayout->setContentsMargins(0, 0, 0, 0);
    auto *destination = new QLabel("Destination: not configured");
    destination->setObjectName("mutedText");
    outputLayout->addWidget(destination);
    auto *buttons = new QHBoxLayout();
    startStreamButton_ = makeToolButton("Start Streaming");
    recordButton_ = makeToolButton("Start Recording");
    startStreamButton_->setEnabled(false);
    recordButton_->setEnabled(false);
    startStreamButton_->setToolTip("Available after capture and output modules are implemented.");
    recordButton_->setToolTip("Available after the recording output is implemented.");
    buttons->addWidget(startStreamButton_);
    buttons->addWidget(recordButton_);
    outputLayout->addLayout(buttons);
    auto *nextStep = new QLabel("Engine lifecycle is scaffolded. Next: video, audio and output pipelines.");
    nextStep->setObjectName("mutedText");
    nextStep->setWordWrap(true);
    outputLayout->addWidget(nextStep);
    bottomRow->addWidget(createPanel("OUTPUT", outputContent), 5);
    root->addLayout(bottomRow, 2);

    auto *footer = new QHBoxLayout();
    auto *footerText = new QLabel("STREAMFORGE ALPHA 0.1.0  •  ENGINE FOUNDATION");
    footerText->setObjectName("footerText");
    footer->addWidget(footerText);
    footer->addStretch();
    footer->addWidget(new QLabel("Direct-to-platform • No relay"));
    root->addLayout(footer);
    setCentralWidget(central);
}

void MainWindow::applyTheme() {
    setStyleSheet(R"(
        QMainWindow, QWidget { background: #101216; color: #e8eaf0; font-size: 13px; }
        QLabel#brand { color: #f5f7fb; font-size: 19px; font-weight: 800; letter-spacing: 2px; }
        QLabel#eyebrow, QLabel#footerText { color: #858c9b; font-size: 10px; font-weight: 700; letter-spacing: 1.4px; }
        QLabel#sectionLabel { color: #aeb5c3; font-size: 10px; font-weight: 800; letter-spacing: 1.3px; }
        QLabel#statusPill { color: #ffca78; background: #332719; border: 1px solid #5b4428; border-radius: 12px; padding: 7px 11px; font-size: 10px; font-weight: 800; }
        QFrame#panel { background: #181c23; border: 1px solid #292f3a; border-radius: 12px; }
        QListWidget { background: #12161c; border: 1px solid #282e39; border-radius: 8px; padding: 5px; outline: none; }
        QListWidget::item { padding: 10px 8px; border-radius: 6px; color: #b8bfcb; }
        QListWidget::item:selected { color: #ffffff; background: #303a50; border-left: 3px solid #8b9fff; }
        QFrame#previewCanvas { background: #090b0f; border: 1px solid #303744; border-radius: 8px; }
        QLabel#previewIcon { color: #69748a; font-size: 42px; }
        QLabel#previewTitle { color: #e8eaf0; font-size: 18px; font-weight: 700; }
        QLabel#mutedText { color: #818a9a; font-size: 11px; }
        QFrame#meterTrack { background: #29313d; border: 1px solid #384252; border-radius: 4px; }
        QPushButton { color: #e7eaf0; background: #252c38; border: 1px solid #394354; border-radius: 7px; padding: 7px 12px; font-weight: 650; }
        QPushButton:disabled { color: #697181; background: #20242b; border-color: #303641; }
    )");
}
