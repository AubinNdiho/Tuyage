Tuyage incoming ringtone
========================

incoming-ringtone.wav is the incoming-call ringtone selected and supplied by
the project owner for Tuyage. It is embedded as PCM WAV so Java Sound can
play it reliably without an MP3 codec.

Behaviour:
- caller: slow synthetic ringback "Dooo... Dooo..."
- callee: this incoming-ringtone.wav loops until Accept / Refuse / Hang up
- once the call state changes, both ringing sounds stop immediately

To change it later, replace incoming-ringtone.wav with another PCM WAV file
and run Clean and Build again.
