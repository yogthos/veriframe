**You have been failing since your last confirmed result.** That is a specific signal, and it usually means one thing: something you added *after* the confirmation broke the state you were building on. The claim you are chasing may be fine. The rules underneath it are the suspect.

Do not keep patching forward. Either roll back to the point where things last worked and take a different route from there, or ship the result you already have.
