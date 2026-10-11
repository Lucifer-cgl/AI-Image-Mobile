# Third-party model notices

The APK does not contain model weights. The following files are distributed as
GitHub Release assets and retain their upstream licenses.

## LCM DreamShaper V7 mobile conversion

- Upstream: https://huggingface.co/SimianLuo/LCM_Dreamshaper_v7
- Upstream license declared by the model repository: MIT
- Source artifact: `LCM_Dreamshaper_v7_4k.safetensors`
- Source SHA-256: `84feab3a32f1d36108b762b25dad483ca9e37f719b23e0cfd0fdc4ad3fd5409b`
- Conversion: stable-diffusion.cpp GGUF, combined CLIP-L, Q4_0
- Converted SHA-256: `7c8b006d8662426ca9296164257038a753c020b61042a680403abd0697fe56bb`

The converted file is split into two raw Release assets because GitHub limits
each individual Release asset to less than 2 GiB. The application verifies each
part, concatenates them in order, then verifies the converted-file hash above.

## Qwen2.5 1.5B Instruct GGUF

- Upstream: https://huggingface.co/Qwen/Qwen2.5-1.5B-Instruct-GGUF
- License: Apache-2.0
- File: `qwen2.5-1.5b-instruct-q4_k_m.gguf`
- SHA-256: `6a1a2eb6d15622bf3c96857206351ba97e1af16c30d7a74ee38970e434e9407e`

## TAESD

- Upstream: https://github.com/madebyollin/taesd
- License: MIT
- Release filename: `taesd.safetensors`
- Upstream filename: `diffusion_pytorch_model.safetensors`
- SHA-256: `db169d69145ec4ff064e49d99c95fa05d3eb04ee453de35824a6d0f325513549`
