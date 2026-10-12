# Dịch Màn Hình (Screen Translator)

Ứng dụng Android dịch chữ trên màn hình (tương tự *Translate On Screen – EZ Screen Translator*).

- Nút nổi trên mọi ứng dụng: **chạm** để dịch toàn màn hình, **nhấn giữ** để chọn vùng cần dịch.
- Nhận dạng chữ (OCR) bằng ML Kit trên máy: Latin, Trung, Nhật, Hàn, Devanagari (tự động chọn).
- Dịch bằng Google Translate, hỗ trợ toàn bộ hơn 130 ngôn ngữ, có chế độ tự phát hiện ngôn ngữ nguồn.
- Bản dịch được vẽ đè đúng vị trí chữ gốc; chạm để đóng, nhấn giữ để sao chép.
- Có ô dịch văn bản nhập tay trong ứng dụng.

## Bộ cài

File APK: [`apk/DichManHinh.apk`](apk/DichManHinh.apk) (được GitHub Actions build tự động sau mỗi lần push).

Yêu cầu Android 8.0 trở lên.

## Build thủ công

```bash
./gradlew assembleRelease
# -> app/build/outputs/apk/release/app-release.apk
```
