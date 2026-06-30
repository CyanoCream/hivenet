import AVFoundation
import ComposeApp
import UIKit

final class IosQrScannerBridge: NSObject, QrScannerBridge {
    private var activeScanner: QrScannerViewController?

    func scanPairingToken(onResult: @escaping (CryptoBridgeResult) -> Void) {
        DispatchQueue.main.async {
            guard AVCaptureDevice.default(for: .video) != nil else {
                onResult(CryptoBridgeResult(ok: false, value: nil, error: "Kamera tidak tersedia"))
                return
            }

            switch AVCaptureDevice.authorizationStatus(for: .video) {
            case .authorized:
                self.presentScanner(onResult: onResult)
            case .notDetermined:
                AVCaptureDevice.requestAccess(for: .video) { granted in
                    DispatchQueue.main.async {
                        if granted {
                            self.presentScanner(onResult: onResult)
                        } else {
                            onResult(CryptoBridgeResult(ok: false, value: nil, error: "Izin kamera ditolak"))
                        }
                    }
                }
            default:
                onResult(CryptoBridgeResult(ok: false, value: nil, error: "Izin kamera belum aktif"))
            }
        }
    }

    private func presentScanner(onResult: @escaping (CryptoBridgeResult) -> Void) {
        guard let presenter = UIApplication.shared.topMostViewController() else {
            onResult(CryptoBridgeResult(ok: false, value: nil, error: "Tidak bisa membuka scanner"))
            return
        }

        let scanner = QrScannerViewController { [weak self] token in
            self?.activeScanner = nil
            if let token = token,
               token.trimmingCharacters(in: .whitespacesAndNewlines).hasPrefix("{") {
                onResult(CryptoBridgeResult(ok: true, value: token, error: nil))
            } else if let token = token, token.hasPrefix("KNET1:PAIRING_") {
                onResult(CryptoBridgeResult(ok: true, value: token, error: nil))
            } else if let token = token {
                onResult(CryptoBridgeResult(ok: false, value: nil, error: "QR bukan token pairing HiveNet. Coba scan ulang."))
            } else {
                onResult(CryptoBridgeResult(ok: false, value: nil, error: "Scan QR dibatalkan"))
            }
        }
        activeScanner = scanner
        presenter.present(scanner, animated: true)
    }
}

private final class QrScannerViewController: UIViewController, AVCaptureMetadataOutputObjectsDelegate {
    private let onFinish: (String?) -> Void
    private let session = AVCaptureSession()
    private var previewLayer: AVCaptureVideoPreviewLayer?
    private var finished = false

    init(onFinish: @escaping (String?) -> Void) {
        self.onFinish = onFinish
        super.init(nibName: nil, bundle: nil)
        modalPresentationStyle = .fullScreen
    }

    required init?(coder: NSCoder) { nil }

    override func viewDidLoad() {
        super.viewDidLoad()
        view.backgroundColor = .black
        configureCamera()
        configureOverlay()
    }

    override func viewDidLayoutSubviews() {
        super.viewDidLayoutSubviews()
        previewLayer?.frame = view.bounds
    }

    override func viewWillAppear(_ animated: Bool) {
        super.viewWillAppear(animated)
        if !session.isRunning {
            DispatchQueue.global(qos: .userInitiated).async { [session] in session.startRunning() }
        }
    }

    override func viewWillDisappear(_ animated: Bool) {
        super.viewWillDisappear(animated)
        if session.isRunning { session.stopRunning() }
    }

    private func configureCamera() {
        guard let device = AVCaptureDevice.default(for: .video),
              let input = try? AVCaptureDeviceInput(device: device),
              session.canAddInput(input) else {
            finish(nil)
            return
        }
        session.addInput(input)

        let output = AVCaptureMetadataOutput()
        guard session.canAddOutput(output) else {
            finish(nil)
            return
        }
        session.addOutput(output)
        output.setMetadataObjectsDelegate(self, queue: DispatchQueue.main)
        output.metadataObjectTypes = [.qr]

        let layer = AVCaptureVideoPreviewLayer(session: session)
        layer.videoGravity = .resizeAspectFill
        layer.frame = view.bounds
        view.layer.addSublayer(layer)
        previewLayer = layer
    }

    private func configureOverlay() {
        let title = UILabel()
        title.text = "Scan QR Pairing"
        title.textColor = .white
        title.font = .boldSystemFont(ofSize: 22)
        title.translatesAutoresizingMaskIntoConstraints = false

        let help = UILabel()
        help.text = "Arahkan kamera ke QR invite atau balasan HiveNet."
        help.textColor = .white
        help.numberOfLines = 0
        help.textAlignment = .center
        help.translatesAutoresizingMaskIntoConstraints = false

        let cancel = UIButton(type: .system)
        cancel.setTitle("Batal", for: .normal)
        cancel.setTitleColor(.white, for: .normal)
        cancel.titleLabel?.font = .boldSystemFont(ofSize: 18)
        cancel.translatesAutoresizingMaskIntoConstraints = false
        cancel.addTarget(self, action: #selector(cancelTapped), for: .touchUpInside)

        view.addSubview(title)
        view.addSubview(help)
        view.addSubview(cancel)

        NSLayoutConstraint.activate([
            title.topAnchor.constraint(equalTo: view.safeAreaLayoutGuide.topAnchor, constant: 24),
            title.centerXAnchor.constraint(equalTo: view.centerXAnchor),
            help.leadingAnchor.constraint(equalTo: view.leadingAnchor, constant: 28),
            help.trailingAnchor.constraint(equalTo: view.trailingAnchor, constant: -28),
            help.bottomAnchor.constraint(equalTo: view.safeAreaLayoutGuide.bottomAnchor, constant: -72),
            cancel.topAnchor.constraint(equalTo: view.safeAreaLayoutGuide.topAnchor, constant: 20),
            cancel.trailingAnchor.constraint(equalTo: view.trailingAnchor, constant: -24),
        ])
    }

    @objc private func cancelTapped() { finish(nil) }

    func metadataOutput(_ output: AVCaptureMetadataOutput, didOutput metadataObjects: [AVMetadataObject], from connection: AVCaptureConnection) {
        guard let object = metadataObjects.first as? AVMetadataMachineReadableCodeObject,
              let value = object.stringValue else { return }
        finish(value)
    }

    private func finish(_ value: String?) {
        guard !finished else { return }
        finished = true
        session.stopRunning()
        dismiss(animated: true) { self.onFinish(value) }
    }
}

private extension UIApplication {
    func topMostViewController(base: UIViewController? = UIApplication.shared.connectedScenes
        .compactMap { ($0 as? UIWindowScene)?.keyWindow }
        .first?.rootViewController) -> UIViewController? {
        if let nav = base as? UINavigationController { return topMostViewController(base: nav.visibleViewController) }
        if let tab = base as? UITabBarController { return topMostViewController(base: tab.selectedViewController) }
        if let presented = base?.presentedViewController { return topMostViewController(base: presented) }
        return base
    }
}
